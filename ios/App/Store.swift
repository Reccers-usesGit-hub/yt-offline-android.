import Foundation
import SwiftUI

struct PlaylistRef: Codable, Hashable {
    var id: String
    var title: String
    var index: Int
}

struct Video: Codable, Identifiable, Hashable {
    var id: String
    var title: String
    var channel: String
    var subs: String
    var duration: Double
    var views: Int
    var publishedText: String
    var description: String
    var likes: String
    var height: Int
    var size: Int64
    var savedAt: Date
    var playlists: [PlaylistRef]
    var videoFile: String
    var audioFile: String?
    /// "like", "dislike" or nil.
    var rating: String?
}

/// Everything is kept in the app's private Application Support folder. It doesn't
/// show up in the Files app or Photos, isn't backed up to iCloud, and is removed
/// if you delete the app.
@MainActor
final class Store: ObservableObject {
    static let shared = Store()

    @Published private(set) var videos: [Video] = []
    @Published private(set) var positions: [String: Double] = [:]

    let root: URL

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = base.appendingPathComponent("offline", isDirectory: true)
        try? FileManager.default.createDirectory(at: root.appendingPathComponent("videos"), withIntermediateDirectories: true)
        var r = root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? r.setResourceValues(values)
        load()
    }

    nonisolated static func dir(_ id: String) -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("offline/videos/\(id)", isDirectory: true)
    }

    func get(_ id: String) -> Video? { videos.first { $0.id == id } }
    func has(_ id: String) -> Bool { get(id) != nil }

    func put(_ v: Video) {
        videos.removeAll { $0.id == v.id }
        videos.append(v)
        saveVideos()
    }

    func update(_ id: String, _ change: (inout Video) -> Void) {
        guard let i = videos.firstIndex(where: { $0.id == id }) else { return }
        change(&videos[i])
        saveVideos()
    }

    func remove(_ ids: [String]) {
        for id in ids { try? FileManager.default.removeItem(at: Self.dir(id)) }
        videos.removeAll { ids.contains($0.id) }
        for id in ids { positions[id] = nil }
        saveVideos()
        savePositions()
    }

    func addToPlaylist(_ id: String, _ ref: PlaylistRef) {
        update(id) { v in
            v.playlists.removeAll { $0.id == ref.id }
            v.playlists.append(ref)
        }
    }

    func removeFromPlaylist(_ id: String, _ listID: String) {
        update(id) { $0.playlists.removeAll { $0.id == listID } }
    }

    func position(_ id: String) -> Double { positions[id] ?? 0 }

    func setPosition(_ id: String, _ seconds: Double, duration: Double) {
        let finished = duration > 0 && seconds >= duration - 8
        positions[id] = (seconds <= 3 || finished) ? nil : seconds
        savePositions()
    }

    /// 0...1 watched fraction for the red bar under thumbnails.
    func watched(_ v: Video) -> Double {
        guard let p = positions[v.id], v.duration > 0 else { return 0 }
        return min(1, p / v.duration)
    }

    var usedBytes: Int64 { videos.reduce(0) { $0 + $1.size } }

    var freeBytes: Int64 {
        let v = try? root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return v?.volumeAvailableCapacityForImportantUsage ?? 0
    }

    // MARK: files

    private var videosFile: URL { root.appendingPathComponent("downloads.json") }
    private var positionsFile: URL { root.appendingPathComponent("positions.json") }

    private func load() {
        let dec = JSONDecoder()
        if let d = try? Data(contentsOf: videosFile), let v = try? dec.decode([Video].self, from: d) { videos = v }
        if let d = try? Data(contentsOf: positionsFile), let p = try? dec.decode([String: Double].self, from: d) { positions = p }
    }

    private func saveVideos() {
        if let d = try? JSONEncoder().encode(videos) { try? d.write(to: videosFile, options: .atomic) }
    }

    private func savePositions() {
        if let d = try? JSONEncoder().encode(positions) { try? d.write(to: positionsFile, options: .atomic) }
    }
}
