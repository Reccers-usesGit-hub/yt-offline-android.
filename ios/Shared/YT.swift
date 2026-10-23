import Foundation

/// Link parsing and the small bits of YouTube data we read directly (title, channel,
/// playlist contents). Stream links come from YouTubeKit in the main app.
enum YT {
    // MARK: - Links

    static func firstURL(in text: String?) -> String? {
        guard let text else { return nil }
        if let r = text.range(of: #"https?://\S+"#, options: .regularExpression) { return String(text[r]) }
        return text
    }

    static func videoID(_ text: String?) -> String? {
        guard let t = firstURL(in: text) else { return nil }
        let p = #"(?:[?&]v=|youtu\.be/|/shorts/|/live/|/embed/|/v/)([A-Za-z0-9_-]{11})"#
        if let id = match(t, p) { return id }
        if t.range(of: #"^[A-Za-z0-9_-]{11}$"#, options: .regularExpression) != nil { return t }
        return nil
    }

    static func listID(_ text: String?) -> String? {
        guard let t = firstURL(in: text) else { return nil }
        return match(t, #"[?&]list=([A-Za-z0-9_-]{10,})"#)
    }

    private static func match(_ s: String, _ pattern: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)),
              m.numberOfRanges > 1, let r = Range(m.range(at: 1), in: s) else { return nil }
        return String(s[r])
    }

    static func thumbURL(_ id: String, max: Bool = false) -> URL {
        URL(string: "https://i.ytimg.com/vi/\(id)/\(max ? "maxresdefault" : "hqdefault").jpg")!
    }

    // MARK: - Video details

    struct Meta: Codable, Hashable {
        var id: String
        var title = ""
        var channel = ""
        var subs = ""
        var duration: Double = 0
        var views = 0
        var publishedText = ""
        var description = ""
        var likes = ""
        var avatar: URL?
    }

    static func meta(_ id: String) async -> Meta {
        var m = Meta(id: id)
        async let player = innertube("player", ["videoId": id])
        async let next = innertube("next", ["videoId": id])
        let (p, n) = await (player, next)

        if let d = p?["videoDetails"] as? [String: Any] {
            m.title = d["title"] as? String ?? ""
            m.channel = d["author"] as? String ?? ""
            m.duration = Double(d["lengthSeconds"] as? String ?? "") ?? 0
            m.views = Int(d["viewCount"] as? String ?? "") ?? 0
            m.description = d["shortDescription"] as? String ?? ""
        }
        if let n {
            if let primary = find(n, "videoPrimaryInfoRenderer") {
                if m.title.isEmpty { m.title = text(find(primary, "title")) ?? "" }
                m.publishedText = text(find(primary, "relativeDateText")) ?? text(find(primary, "dateText")) ?? ""
            }
            if let owner = find(n, "videoOwnerRenderer") {
                if m.channel.isEmpty { m.channel = text(find(owner, "title")) ?? "" }
                m.subs = text(find(owner, "subscriberCountText")) ?? ""
                if let thumbs = (find(owner, "thumbnail") as? [String: Any])?["thumbnails"] as? [[String: Any]],
                   let u = (thumbs.last?["url"] as? String).flatMap(fixURL) {
                    m.avatar = u
                }
            }
            if let like = find(n, "likeButtonViewModel") {
                m.likes = findString(like, "title") ?? ""
            }
            if m.description.isEmpty, let sec = find(n, "videoSecondaryInfoRenderer") {
                m.description = text(find(sec, "attributedDescription")) ?? ""
            }
        }
        return m
    }

    // MARK: - Playlists

    struct PlaylistEntry: Codable, Hashable {
        var id: String
        var title: String
        var channel: String
        var duration: Double
    }

    struct Playlist: Codable, Hashable {
        var id: String
        var title: String
        var entries: [PlaylistEntry]
    }

    static func playlist(_ listID: String) async -> Playlist? {
        guard var page = await innertube("browse", ["browseId": "VL" + listID]) else { return nil }
        let title = text(find(page, "playlistMetadataRenderer").flatMap { find($0, "title") })
            ?? findString(find(page, "pageHeaderRenderer") ?? [String: Any](), "pageTitle")
            ?? "Playlist"
        var entries: [PlaylistEntry] = []
        var seen = Set<String>()
        for _ in 0..<40 {   // up to ~4000 videos
            for r in collect(page, "playlistVideoRenderer") {
                guard let id = r["videoId"] as? String, !seen.contains(id) else { continue }
                if r["isPlayable"] as? Bool == false { continue }
                seen.insert(id)
                entries.append(PlaylistEntry(
                    id: id,
                    title: text(r["title"]) ?? "",
                    channel: text(r["shortBylineText"]) ?? "",
                    duration: Double(r["lengthSeconds"] as? String ?? "") ?? 0
                ))
            }
            guard let cont = find(page, "continuationCommand") as? [String: Any],
                  let token = cont["token"] as? String,
                  let more = await innertube("browse", ["continuation": token]) else { break }
            page = more
        }
        return entries.isEmpty ? nil : Playlist(id: listID, title: title, entries: entries)
    }

    // MARK: - InnerTube

    private static func innertube(_ endpoint: String, _ body: [String: Any]) async -> [String: Any]? {
        var b = body
        b["context"] = ["client": ["clientName": "WEB", "clientVersion": "2.20251001.01.00", "hl": "en", "gl": "US"]]
        var req = URLRequest(url: URL(string: "https://www.youtube.com/youtubei/v1/\(endpoint)?prettyPrint=false")!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
                     forHTTPHeaderField: "User-Agent")
        req.httpBody = try? JSONSerialization.data(withJSONObject: b)
        req.timeoutInterval = 20
        guard let (data, _) = try? await URLSession.shared.data(for: req) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func fixURL(_ s: String) -> URL? {
        URL(string: s.hasPrefix("//") ? "https:" + s : s)
    }

    /// First value stored under `key`, searching depth-first.
    static func find(_ obj: Any, _ key: String) -> Any? {
        if let d = obj as? [String: Any] {
            if let v = d[key] { return v }
            for v in d.values { if let r = find(v, key) { return r } }
        } else if let a = obj as? [Any] {
            for v in a { if let r = find(v, key) { return r } }
        }
        return nil
    }

    static func findString(_ obj: Any, _ key: String) -> String? {
        if let d = obj as? [String: Any] {
            if let s = d[key] as? String { return s }
            if let t = text(d[key]) { return t }
            for v in d.values { if let r = findString(v, key) { return r } }
        } else if let a = obj as? [Any] {
            for v in a { if let r = findString(v, key) { return r } }
        }
        return nil
    }

    static func collect(_ obj: Any, _ key: String) -> [[String: Any]] {
        var out: [[String: Any]] = []
        func walk(_ o: Any) {
            if let d = o as? [String: Any] {
                if let hit = d[key] as? [String: Any] { out.append(hit) }
                for v in d.values { walk(v) }
            } else if let a = o as? [Any] {
                for v in a { walk(v) }
            }
        }
        walk(obj)
        return out
    }

    /// YouTube text objects: {simpleText}, {runs:[{text}]}, or {content}.
    static func text(_ obj: Any?) -> String? {
        if let s = obj as? String { return s.isEmpty ? nil : s }
        guard let d = obj as? [String: Any] else { return nil }
        if let s = d["simpleText"] as? String { return s }
        if let runs = d["runs"] as? [[String: Any]] {
            let s = runs.compactMap { $0["text"] as? String }.joined()
            return s.isEmpty ? nil : s
        }
        if let s = d["content"] as? String { return s }
        return nil
    }
}

/// What the share sheet hands to the main app.
struct DownloadRequest: Hashable {
    var videoID: String?
    var listID: String?
    var wholePlaylist: Bool
    var quality: Int

    static let scheme = "ytoffline"

    var url: URL {
        var c = URLComponents()
        c.scheme = Self.scheme
        c.host = "download"
        c.queryItems = [
            videoID.map { URLQueryItem(name: "v", value: $0) },
            listID.map { URLQueryItem(name: "list", value: $0) },
            URLQueryItem(name: "whole", value: wholePlaylist ? "1" : "0"),
            URLQueryItem(name: "q", value: String(quality)),
        ].compactMap { $0 }
        return c.url!
    }

    init(videoID: String?, listID: String?, wholePlaylist: Bool, quality: Int) {
        self.videoID = videoID
        self.listID = listID
        self.wholePlaylist = wholePlaylist
        self.quality = quality
    }

    init?(url: URL) {
        guard url.scheme == Self.scheme, let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        func q(_ n: String) -> String? { c.queryItems?.first { $0.name == n }?.value }
        videoID = q("v")
        listID = q("list")
        wholePlaylist = q("whole") == "1"
        quality = Int(q("q") ?? "") ?? 1080
        if videoID == nil && listID == nil { return nil }
    }
}
