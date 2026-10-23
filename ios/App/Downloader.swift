import AVFoundation
import Foundation
import UIKit
import YouTubeKit

struct DlError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

struct DlJob: Identifiable, Hashable {
    var id: String          // video id
    var title: String
    var playlist: PlaylistRef?
    var quality: Int
    var done: Int64 = 0
    var total: Int64 = 0
    var stage = "Waiting…"
    var failed: String?
    var running = false

    var progress: Double { total > 0 ? min(1, Double(done) / Double(total)) : 0 }
}

/// Download queue: two videos at a time, each file fetched in parallel pieces (much faster
/// than one long stream, which YouTube slows down).
@MainActor
final class Downloader: ObservableObject {
    static let shared = Downloader()

    @Published private(set) var jobs: [DlJob] = []
    @Published private(set) var banner: String?

    private var tasks: [String: Task<Void, Never>] = [:]
    private var bannerTask: Task<Void, Never>?
    private let maxParallel = 2

    // MARK: queue

    func handle(_ r: DownloadRequest) {
        if r.wholePlaylist, let list = r.listID {
            Task { await enqueuePlaylist(list, quality: r.quality) }
        } else if let v = r.videoID {
            if Store.shared.has(v) { show("Already in your downloads"); return }
            enqueue(v, title: "", quality: r.quality, playlist: nil)
            show("Downloading…")
        }
    }

    func enqueue(_ id: String, title: String, quality: Int, playlist: PlaylistRef?) {
        if Store.shared.has(id) {
            if let playlist { Store.shared.addToPlaylist(id, playlist) }
            return
        }
        if let i = jobs.firstIndex(where: { $0.id == id }) {
            if jobs[i].failed != nil { jobs[i].failed = nil; jobs[i].stage = "Waiting…" }
            if jobs[i].playlist == nil, let playlist { jobs[i].playlist = playlist }
        } else {
            jobs.append(DlJob(id: id, title: title, playlist: playlist, quality: quality))
        }
        pump()
    }

    func enqueuePlaylist(_ listID: String, quality: Int) async {
        show("Loading playlist…")
        guard let p = await YT.playlist(listID) else { show("Couldn't load this playlist"); return }
        var added = 0, had = 0
        for (i, e) in p.entries.enumerated() {
            let ref = PlaylistRef(id: p.id, title: p.title, index: i)
            if Store.shared.has(e.id) {
                Store.shared.addToPlaylist(e.id, ref)
                had += 1
            } else {
                enqueue(e.id, title: e.title, quality: quality, playlist: ref)
                added += 1
            }
        }
        show(added > 0
             ? "Downloading \(added) video\(added == 1 ? "" : "s") from “\(p.title)”"
             : "Playlist added to your downloads" + (had > 0 ? " (\(had) already downloaded)" : ""))
    }

    func cancel(_ id: String) {
        tasks[id]?.cancel()
        tasks[id] = nil
        jobs.removeAll { $0.id == id }
        try? FileManager.default.removeItem(at: Store.dir(id))
        pump()
    }

    func retry(_ id: String) {
        guard let i = jobs.firstIndex(where: { $0.id == id }) else { return }
        jobs[i].failed = nil
        jobs[i].stage = "Waiting…"
        jobs[i].done = 0
        pump()
    }

    func show(_ text: String) {
        banner = text
        bannerTask?.cancel()
        bannerTask = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if !Task.isCancelled { banner = nil }
        }
    }

    private func pump() {
        var free = maxParallel - jobs.filter(\.running).count
        for i in jobs.indices where free > 0 && !jobs[i].running && jobs[i].failed == nil {
            jobs[i].running = true
            free -= 1
            let job = jobs[i]
            tasks[job.id] = Task { await self.run(job) }
        }
    }

    private func edit(_ id: String, _ change: (inout DlJob) -> Void) {
        if let i = jobs.firstIndex(where: { $0.id == id }) { change(&jobs[i]) }
    }

    // MARK: one video

    private func run(_ job: DlJob) async {
        // Ask iOS for a little extra time if you switch apps mid-download.
        let bg = BgTask()
        bg.id = UIApplication.shared.beginBackgroundTask(withName: "download") { MainActor.assumeIsolated { bg.end() } }
        do {
            try await download(job)
            jobs.removeAll { $0.id == job.id }
        } catch is CancellationError {
            // removed by cancel()
        } catch {
            if !Task.isCancelled {
                edit(job.id) {
                    $0.running = false
                    $0.failed = (error as? DlError)?.message ?? "Couldn't download this video"
                }
                try? FileManager.default.removeItem(at: Store.dir(job.id))
            }
        }
        tasks[job.id] = nil
        bg.end()
        pump()
    }

    private func download(_ job: DlJob) async throws {
        let id = job.id
        edit(id) { $0.stage = "Getting video info…" }

        async let metaTask = YT.meta(id)
        let streams: [YouTubeKit.Stream]
        do {
            streams = try await YouTube(videoID: id, methods: [.local, .remote]).streams
        } catch {
            throw DlError("YouTube didn't give a download link for this video")
        }
        let meta = await metaTask
        try Task.checkCancellation()
        guard let pick = Self.pick(streams, maxHeight: job.quality) else {
            throw DlError("No version of this video plays on this device")
        }
        if !meta.title.isEmpty { edit(id) { $0.title = meta.title } }

        let dir = Store.dir(id)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if !(await Fetcher.saveSmall(YT.thumbURL(id, max: true), to: dir.appendingPathComponent("thumb.jpg"))) {
            _ = await Fetcher.saveSmall(YT.thumbURL(id), to: dir.appendingPathComponent("thumb.jpg"))
        }
        if let a = meta.avatar { _ = await Fetcher.saveSmall(a, to: dir.appendingPathComponent("avatar.jpg")) }

        edit(id) { $0.stage = ""; $0.done = 0; $0.total = 0 }
        let vName = "video.mp4"
        let aName: String? = pick.audio == nil ? nil : "audio.m4a"
        let onTotal: @Sendable (Int64) -> Void = { n in Task { @MainActor in self.edit(id) { $0.total += n } } }
        let onBytes: @Sendable (Int64) -> Void = { n in Task { @MainActor in self.edit(id) { $0.done += n } } }

        try await withThrowingTaskGroup(of: Void.self) { g in
            g.addTask { try await Fetcher.fetch(pick.video.url, to: dir.appendingPathComponent(vName), onTotal: onTotal, onBytes: onBytes) }
            if let a = pick.audio, let aName {
                g.addTask { try await Fetcher.fetch(a.url, to: dir.appendingPathComponent(aName), onTotal: onTotal, onBytes: onBytes) }
            }
            try await g.waitForAll()
        }
        try Task.checkCancellation()

        var duration = meta.duration
        if duration <= 0 {
            duration = (try? await AVURLAsset(url: dir.appendingPathComponent(vName)).load(.duration).seconds) ?? 0
        }
        let size = [vName, aName].compactMap { $0 }.reduce(Int64(0)) { sum, n in
            let attrs = try? FileManager.default.attributesOfItem(atPath: dir.appendingPathComponent(n).path)
            return sum + ((attrs?[.size] as? NSNumber)?.int64Value ?? 0)
        }
        let current = jobs.first { $0.id == id }
        let playlist = current?.playlist ?? job.playlist
        let v = Video(
            id: id,
            title: meta.title.isEmpty ? (current?.title ?? job.title) : meta.title,
            channel: meta.channel, subs: meta.subs,
            duration: duration, views: meta.views,
            publishedText: meta.publishedText, description: meta.description, likes: meta.likes,
            height: pick.height, size: size, savedAt: Date(),
            playlists: playlist.map { [$0] } ?? [],
            videoFile: vName, audioFile: aName, rating: nil
        )
        Store.shared.put(v)
        if playlist == nil { show("Downloaded: \(v.title)") }
    }

    // MARK: choosing streams

    struct Pick {
        let video: YouTubeKit.Stream
        let audio: YouTubeKit.Stream?
        let height: Int
    }

    /// Best MP4 picture up to `maxHeight` that this device can play, plus the best M4A sound.
    nonisolated static func pick(_ streams: [YouTubeKit.Stream], maxHeight: Int) -> Pick? {
        func codecScore(_ s: YouTubeKit.Stream) -> Int {
            guard let c = s.videoCodec else { return 0 }
            switch c {
            case .avc1: return 2
            case .av1: return 1
            default: return 0
            }
        }
        let videos = streams.filterVideoOnly()
            .filter { s in
                let h = s.videoResolution ?? 0
                return s.isNativelyPlayable && s.fileExtension == .mp4 && h > 0 && h <= maxHeight
            }
            .sorted { a, b in
                ((a.videoResolution ?? 0), codecScore(a)) > ((b.videoResolution ?? 0), codecScore(b))
            }
        let audio = streams.filterAudioOnly()
            .filter { $0.fileExtension == .m4a && $0.isNativelyPlayable }
            .highestAudioBitrateStream()
        if let v = videos.first, let audio {
            return Pick(video: v, audio: audio, height: v.videoResolution ?? 0)
        }
        let combined = streams.filterVideoAndAudio()
            .filter { $0.isNativelyPlayable && $0.fileExtension == .mp4 }
        if let c = combined.filter({ ($0.videoResolution ?? 0) <= maxHeight }).highestResolutionStream()
            ?? combined.lowestResolutionStream() {
            return Pick(video: c, audio: nil, height: c.videoResolution ?? 0)
        }
        return nil
    }
}

final class BgTask: @unchecked Sendable {
    var id = UIBackgroundTaskIdentifier.invalid
    @MainActor func end() {
        if id != .invalid { UIApplication.shared.endBackgroundTask(id); id = .invalid }
    }
}

// MARK: - Fetching files in parallel pieces

enum Fetcher {
    static let piece: Int64 = 4 << 20       // 4 MB
    static let parallel = 4

    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.httpMaximumConnectionsPerHost = 8
        c.timeoutIntervalForRequest = 30
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.urlCache = nil
        return URLSession(configuration: c)
    }()

    final class Writer: @unchecked Sendable {
        private let handle: FileHandle
        private let lock = NSLock()
        init(_ url: URL) throws {
            FileManager.default.createFile(atPath: url.path, contents: nil)
            handle = try FileHandle(forWritingTo: url)
        }
        func write(_ data: Data, at offset: Int64) throws {
            lock.lock(); defer { lock.unlock() }
            try handle.seek(toOffset: UInt64(offset))
            try handle.write(contentsOf: data)
        }
        func close() { try? handle.close() }
    }

    static func fetch(_ url: URL, to file: URL,
                      onTotal: @escaping @Sendable (Int64) -> Void,
                      onBytes: @escaping @Sendable (Int64) -> Void) async throws {
        let writer = try Writer(file)
        defer { writer.close() }

        let (first, total) = try await get(url, from: 0, to: piece - 1)
        try writer.write(first, at: 0)
        let size = total ?? Int64(first.count)
        onTotal(size)
        onBytes(Int64(first.count))
        guard size > Int64(first.count) else { return }

        var ranges: [(Int64, Int64)] = []
        var start = Int64(first.count)
        while start < size {
            ranges.append((start, min(start + piece, size) - 1))
            start += piece
        }
        let one: @Sendable ((Int64, Int64)) async throws -> Void = { r in
            let (d, _) = try await get(url, from: r.0, to: r.1)
            guard Int64(d.count) == r.1 - r.0 + 1 else { throw DlError("The download was cut off. Try again.") }
            try writer.write(d, at: r.0)
            onBytes(Int64(d.count))
        }
        try await withThrowingTaskGroup(of: Void.self) { g in
            var next = 0
            while next < min(parallel, ranges.count) {
                let r = ranges[next]
                g.addTask { try await one(r) }
                next += 1
            }
            while try await g.next() != nil {
                if next < ranges.count {
                    let r = ranges[next]
                    g.addTask { try await one(r) }
                    next += 1
                }
            }
        }
    }

    /// One ranged request, retried a few times. Returns the data and the full file size.
    static func get(_ url: URL, from: Int64, to: Int64) async throws -> (Data, Int64?) {
        var last: Error = DlError("Download failed")
        for attempt in 0..<4 {
            try Task.checkCancellation()
            do {
                var req = URLRequest(url: url)
                req.setValue("bytes=\(from)-\(to)", forHTTPHeaderField: "Range")
                let (data, resp) = try await session.data(for: req)
                let http = resp as? HTTPURLResponse
                switch http?.statusCode ?? 0 {
                case 206:
                    let total = http?.value(forHTTPHeaderField: "Content-Range")?
                        .split(separator: "/").last.flatMap { Int64($0) }
                    return (data, total)
                case 200 where from == 0:
                    return (data, Int64(data.count))
                case let code:
                    last = DlError("YouTube refused the download (error \(code)). Try again later.")
                }
            } catch {
                if Task.isCancelled { throw CancellationError() }
                last = error
            }
            try await Task.sleep(nanoseconds: UInt64(attempt + 1) * 800_000_000)
        }
        throw last
    }

    /// Thumbnails and avatars.
    static func saveSmall(_ url: URL, to file: URL) async -> Bool {
        guard let (data, resp) = try? await session.data(from: url),
              (resp as? HTTPURLResponse)?.statusCode == 200, data.count > 2000 else { return false }
        return (try? data.write(to: file)) != nil
    }
}
