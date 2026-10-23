import SwiftUI

/// The "Download" card: video preview, this-video / whole-playlist choice, quality.
/// Used by the share extension and by "Paste link" inside the app.
struct DownloadSheet: View {
    let videoID: String?
    let listID: String?
    var alreadyHave: (String) -> Bool = { _ in false }
    let onDownload: (DownloadRequest) -> Void
    let onClose: () -> Void

    @AppStorage("quality") private var quality = 1080
    @State private var meta: YT.Meta?
    @State private var playlist: YT.Playlist?
    @State private var playlistFailed = false
    @State private var whole = false

    static let qualities = [2160, 1440, 1080, 720, 480, 360]

    private var have: Bool { videoID.map(alreadyHave) ?? false }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Download").font(.title3.bold())
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "xmark").font(.body.weight(.semibold)).frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .glass(Circle(), interactive: true)
                .accessibilityLabel("Close")
            }

            if let videoID {
                HStack(alignment: .top, spacing: 12) {
                    AsyncImage(url: YT.thumbURL(videoID)) { img in
                        img.resizable().aspectRatio(16 / 9, contentMode: .fill)
                    } placeholder: {
                        Rectangle().fill(.quaternary)
                    }
                    .frame(width: 136, height: 76.5)
                    .lifted(10)
                    .overlay(alignment: .bottomTrailing) {
                        if let d = meta?.duration, d > 0 { GlassBadge(text: Fmt.duration(d)).padding(5) }
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(meta?.title.isEmpty == false ? meta!.title : "Loading…")
                            .font(.subheadline.weight(.medium)).lineLimit(3)
                        if let c = meta?.channel, !c.isEmpty {
                            Text(c).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }

            if listID != nil {
                VStack(spacing: 6) {
                    if videoID != nil {
                        choice("This video", nil, selected: !whole) { whole = false }
                    }
                    choice("Whole playlist", playlistText, selected: whole || videoID == nil) { whole = true }
                }
            }

            if have && !whole {
                Text("This video is already in your downloads.")
                    .font(.subheadline).foregroundStyle(.secondary)
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Quality").font(.subheadline.weight(.semibold))
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(Self.qualities, id: \.self) { q in
                                GlassChip(title: label(q), selected: quality == q) { quality = q }
                            }
                        }
                        .padding(.vertical, 2)
                    }
                    Text("If a video doesn't have this quality, the closest lower one is used.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }

            Button {
                onDownload(DownloadRequest(videoID: whole ? nil : videoID, listID: listID,
                                           wholePlaylist: whole || videoID == nil, quality: quality))
            } label: {
                Text(buttonText).font(.body.weight(.semibold)).frame(maxWidth: .infinity).padding(.vertical, 4)
            }
            .prominentGlassButton()
            .tint(.red)
            .disabled(!ready)
        }
        .padding(20)
        .task {
            whole = videoID == nil
            if let videoID, meta == nil { meta = await YT.meta(videoID) }
        }
        .task {
            if let listID, playlist == nil {
                playlist = await YT.playlist(listID)
                playlistFailed = playlist == nil
            }
        }
    }

    private var ready: Bool {
        if whole || videoID == nil { return playlist != nil }
        return !have
    }

    private var buttonText: String {
        if (whole || videoID == nil) && playlist == nil && !playlistFailed { return "Loading playlist…" }
        if (whole || videoID == nil), let p = playlist { return "Download \(p.entries.count) videos" }
        return "Download"
    }

    private var playlistText: String {
        if let p = playlist {
            let had = p.entries.filter { alreadyHave($0.id) }.count
            return "\(p.title) · \(p.entries.count) videos" + (had > 0 ? " · \(had) already downloaded" : "")
        }
        return playlistFailed ? "Couldn't load this playlist" : "Loading playlist…"
    }

    private func label(_ q: Int) -> String {
        switch q {
        case 2160: return "4K"
        case 1440: return "1440p"
        default: return "\(q)p"
        }
    }

    private func choice(_ title: String, _ subtitle: String?, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.title3)
                    .foregroundStyle(selected ? Color.red : Color.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.weight(.medium))
                    if let subtitle { Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .contentShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .glass(RoundedRectangle(cornerRadius: 14, style: .continuous), interactive: true)
    }
}
