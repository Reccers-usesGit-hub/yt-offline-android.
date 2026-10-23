import AVFoundation
import AVKit
import SwiftUI
import UIKit

enum Media {
    /// Picture and sound are saved as two files; this joins them for playback.
    static func asset(for v: Video) async throws -> AVAsset {
        let dir = Store.dir(v.id)
        let videoAsset = AVURLAsset(url: dir.appendingPathComponent(v.videoFile))
        guard let audioFile = v.audioFile else { return videoAsset }
        let audioAsset = AVURLAsset(url: dir.appendingPathComponent(audioFile))

        let comp = AVMutableComposition()
        if let vt = try await videoAsset.loadTracks(withMediaType: .video).first,
           let ct = comp.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) {
            let range = try await vt.load(.timeRange)
            try ct.insertTimeRange(range, of: vt, at: .zero)
            ct.preferredTransform = try await vt.load(.preferredTransform)
        }
        if let at = try await audioAsset.loadTracks(withMediaType: .audio).first,
           let ct = comp.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) {
            let range = try await at.load(.timeRange)
            try ct.insertTimeRange(range, of: at, at: .zero)
        }
        return comp
    }

    /// Makes one normal MP4 (picture + sound) in a temporary folder, for sending to someone.
    static func exportForSharing(_ v: Video) async throws -> URL {
        let fm = FileManager.default
        let dir = fm.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? fm.removeItem(at: dir)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let cleaned = v.title.components(separatedBy: CharacterSet(charactersIn: "/\\:?%*|\"<>")).joined()
        let name = String(cleaned.trimmingCharacters(in: .whitespaces).prefix(60))
        let out = dir.appendingPathComponent((name.isEmpty ? v.id : name) + ".mp4")

        if v.audioFile == nil {
            try fm.copyItem(at: Store.dir(v.id).appendingPathComponent(v.videoFile), to: out)
            return out
        }
        let asset = try await asset(for: v)
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetPassthrough) else {
            throw DlError("Couldn't prepare this video")
        }
        if #available(iOS 18.0, *) {
            try await export.export(to: out, as: .mp4)
        } else {
            export.outputURL = out
            export.outputFileType = .mp4
            await export.export()
            if export.status != .completed { throw export.error ?? DlError("Couldn't prepare this video") }
        }
        return out
    }
}

/// Shares a downloaded video as a file (AirDrop, Messages, WhatsApp, Save to Files…).
@MainActor
final class Sharer: ObservableObject {
    struct File: Identifiable {
        let id = UUID()
        let url: URL
    }

    static let shared = Sharer()
    @Published var preparing = false
    @Published var file: File?

    func share(_ v: Video) {
        guard !preparing else { return }
        preparing = true
        Task {
            do {
                file = File(url: try await Media.exportForSharing(v))
            } catch {
                Downloader.shared.show("Couldn't prepare this video for sharing")
            }
            preparing = false
        }
    }
}

struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

// MARK: - Player

@MainActor
final class PlayerModel: ObservableObject {
    let player = AVPlayer()
    var onEnd: (() -> Void)?
    private var currentID: String?
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?

    func load(_ v: Video) async {
        save()
        removeObservers()
        currentID = v.id
        guard let asset = try? await Media.asset(for: v), currentID == v.id else { return }
        let item = AVPlayerItem(asset: asset)
        player.replaceCurrentItem(with: item)
        let start = Store.shared.position(v.id)
        if start > 0 { await player.seek(to: CMTime(seconds: start, preferredTimescale: 600)) }
        player.play()

        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 5, preferredTimescale: 1), queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.save() }
        }
        let id = v.id
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                let d = Store.shared.get(id)?.duration ?? 0
                Store.shared.setPosition(id, d, duration: d)
                self?.onEnd?()
            }
        }
    }

    func save() {
        guard let id = currentID, player.currentItem != nil else { return }
        let t = player.currentTime().seconds
        guard t.isFinite else { return }
        Store.shared.setPosition(id, t, duration: Store.shared.get(id)?.duration ?? 0)
    }

    func stop() {
        save()
        player.pause()
        removeObservers()
        currentID = nil
    }

    private func removeObservers() {
        if let t = timeObserver { player.removeTimeObserver(t); timeObserver = nil }
        if let e = endObserver { NotificationCenter.default.removeObserver(e); endObserver = nil }
    }
}

struct PlayerView: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = player
        vc.allowsPictureInPicturePlayback = true
        vc.canStartPictureInPictureAutomaticallyFromInline = true
        vc.videoGravity = .resizeAspect
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {
        if vc.player !== player { vc.player = player }
    }
}

// MARK: - Local images

enum ImageCache {
    static let cache = NSCache<NSURL, UIImage>()

    static func image(_ url: URL) -> UIImage? {
        if let i = cache.object(forKey: url as NSURL) { return i }
        guard let i = UIImage(contentsOfFile: url.path) else { return nil }
        cache.setObject(i, forKey: url as NSURL)
        return i
    }
}

struct LocalImage: View {
    let url: URL
    var body: some View {
        if let img = ImageCache.image(url) {
            Image(uiImage: img).resizable().scaledToFill()
        } else {
            Rectangle().fill(Color(.secondarySystemFill))
        }
    }
}
