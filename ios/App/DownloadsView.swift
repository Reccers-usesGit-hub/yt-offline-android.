import SwiftUI

enum Route: Hashable {
    case watch(id: String, list: String?)
}

struct PasteRequest: Identifiable {
    let id = UUID()
    let videoID: String?
    let listID: String?
}

struct PlGroup: Identifiable {
    let id: String
    var title: String
    var videos: [Video]
    var latest: Date
}

struct DownloadsView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var dl: Downloader
    @EnvironmentObject var sharer: Sharer

    @AppStorage("filter") private var filter = "all"
    @State private var path: [Route] = []
    @State private var paste: PasteRequest?
    @State private var showSettings = false
    @State private var confirm: ConfirmRemove?

    struct ConfirmRemove: Identifiable {
        let id = UUID()
        let title: String
        let ids: [String]
    }

    private var groups: [PlGroup] {
        var map: [String: PlGroup] = [:]
        for v in store.videos {
            for ref in v.playlists {
                var g = map[ref.id] ?? PlGroup(id: ref.id, title: ref.title, videos: [], latest: .distantPast)
                g.videos.append(v)
                g.latest = max(g.latest, v.savedAt)
                map[ref.id] = g
            }
        }
        return map.values
            .map { g in
                var g = g
                g.videos.sort { index($0, in: g.id) < index($1, in: g.id) }
                return g
            }
            .sorted { $0.latest > $1.latest }
    }

    private func index(_ v: Video, in list: String) -> Int {
        v.playlists.first { $0.id == list }?.index ?? .max
    }

    private let columns = [GridItem(.adaptive(minimum: 280, maximum: 520), spacing: 18, alignment: .top)]

    var body: some View {
        NavigationStack(path: $path) {
            let groups = self.groups
            let selected = groups.first { $0.id == filter }
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if !groups.isEmpty { chips(groups) }
                    if let g = selected {
                        playlistPage(g)
                    } else if store.videos.isEmpty && dl.jobs.isEmpty {
                        emptyState
                    } else {
                        allGrid(groups)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 32)
            }
            .navigationTitle("Downloads")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    PasteButton(payloadType: String.self) { strings in
                        Task { @MainActor in handlePaste(strings.first) }
                    }
                    .labelStyle(.iconOnly)
                    .buttonBorderShape(.circle)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Settings")
                }
            }
            .navigationDestination(for: Route.self) { r in
                switch r {
                case let .watch(id, list): WatchView(id: id, listID: list)
                }
            }
        }
        .sheet(item: $paste) { p in
            DownloadSheet(videoID: p.videoID, listID: p.listID,
                          alreadyHave: { store.has($0) },
                          onDownload: { r in paste = nil; dl.handle(r) },
                          onClose: { paste = nil })
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showSettings) { SettingsView() }
        .confirmationDialog(confirm?.title ?? "", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }),
                            titleVisibility: .visible, presenting: confirm) { c in
            Button("Remove", role: .destructive) {
                store.remove(c.ids)
                if store.videos.isEmpty { filter = "all" }
            }
        }
    }

    // MARK: pieces

    private func chips(_ groups: [PlGroup]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                GlassChip(title: "All", selected: !groups.contains { $0.id == filter }) { filter = "all" }
                ForEach(groups) { g in
                    GlassChip(title: g.title, selected: filter == g.id) { filter = g.id }
                }
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 2)
        }
    }

    private enum Item: Identifiable {
        case video(Video)
        case playlist(PlGroup)
        var id: String {
            switch self {
            case .video(let v): return "v-" + v.id
            case .playlist(let g): return "p-" + g.id
            }
        }
        var date: Date {
            switch self {
            case .video(let v): return v.savedAt
            case .playlist(let g): return g.latest
            }
        }
    }

    @ViewBuilder
    private func allGrid(_ groups: [PlGroup]) -> some View {
        let loose = store.videos.filter { $0.playlists.isEmpty }
        let items = (loose.map(Item.video) + groups.map(Item.playlist)).sorted { $0.date > $1.date }
        let looseJobs = dl.jobs.filter { $0.playlist == nil }
        let listJobs = Dictionary(grouping: dl.jobs.filter { $0.playlist != nil }) { $0.playlist!.id }

        LazyVGrid(columns: columns, spacing: 24) {
            ForEach(looseJobs) { j in JobCard(job: j) }
            ForEach(Array(listJobs.keys).sorted(), id: \.self) { key in
                let jobs = listJobs[key]!
                PlaylistJobCard(title: jobs[0].playlist?.title ?? "Playlist", jobs: jobs)
            }
            ForEach(items) { item in
                switch item {
                case .video(let v):
                    VideoCard(v: v, watched: store.watched(v), menu: videoMenu(v, list: nil)) {
                        path.append(.watch(id: v.id, list: nil))
                    }
                case .playlist(let g):
                    PlaylistCard(title: g.title, videos: g.videos, menu: [
                        MenuAction(title: "Play all", systemImage: "play.fill") {
                            if let first = g.videos.first { path.append(.watch(id: first.id, list: g.id)) }
                        },
                        MenuAction(title: "View full playlist", systemImage: "list.bullet") { filter = g.id },
                        MenuAction(title: "Remove playlist from downloads", systemImage: "trash", role: .destructive) {
                            confirm = ConfirmRemove(title: "Remove “\(g.title)” (\(g.videos.count) videos) from downloads?",
                                                    ids: g.videos.map(\.id))
                        },
                    ]) { filter = g.id }
                }
            }
        }
    }

    @ViewBuilder
    private func playlistPage(_ g: PlGroup) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(g.title).font(.title2.bold()).lineLimit(2)
                Text("\(g.videos.count) video\(g.videos.count == 1 ? "" : "s") · \(Fmt.bytes(g.videos.reduce(0) { $0 + $1.size }))")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                let start = g.videos.first { store.watched($0) < 0.95 } ?? g.videos.first
                if let start { path.append(.watch(id: start.id, list: g.id)) }
            } label: {
                Label("Play all", systemImage: "play.fill").font(.subheadline.weight(.semibold)).padding(.horizontal, 6)
            }
            .prominentGlassButton()
            .tint(.red)
        }
        .padding(.top, 4)

        let jobs = dl.jobs.filter { $0.playlist?.id == g.id }
        LazyVStack(spacing: 6) {
            ForEach(Array(g.videos.enumerated()), id: \.element.id) { i, v in
                VideoRow(v: v, watched: store.watched(v), index: i + 1, menu: [
                    MenuAction(title: "Share video file", systemImage: "square.and.arrow.up") { sharer.share(v) },
                    MenuAction(title: "Remove from this playlist", systemImage: "minus.circle") { store.removeFromPlaylist(v.id, g.id) },
                    MenuAction(title: "Remove from downloads", systemImage: "trash", role: .destructive) {
                        confirm = ConfirmRemove(title: "Remove “\(v.title)” from downloads?", ids: [v.id])
                    },
                ]) { path.append(.watch(id: v.id, list: g.id)) }
            }
            if !jobs.isEmpty {
                LazyVGrid(columns: columns, spacing: 24) {
                    ForEach(jobs) { j in JobCard(job: j) }
                }
                .padding(.top, 12)
            }
        }
    }

    private func videoMenu(_ v: Video, list: String?) -> [MenuAction] {
        [
            MenuAction(title: "Share video file", systemImage: "square.and.arrow.up") { sharer.share(v) },
            MenuAction(title: "Remove from downloads", systemImage: "trash", role: .destructive) {
                confirm = ConfirmRemove(title: "Remove “\(v.title)” from downloads?", ids: [v.id])
            },
        ]
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "arrow.down.circle")
                .font(.system(size: 56, weight: .light))
                .foregroundStyle(.secondary)
                .padding(.top, 80)
            Text("No downloads yet").font(.title3.bold())
            Text("In the YouTube app, tap **Share** → **Offline Downloads**.\nOr copy a video link and tap Paste.")
                .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            PasteButton(payloadType: String.self) { strings in
                Task { @MainActor in handlePaste(strings.first) }
            }
            .buttonBorderShape(.capsule)
            .padding(.top, 6)
        }
        .frame(maxWidth: .infinity)
    }

    private func handlePaste(_ text: String?) {
        let v = YT.videoID(text), l = YT.listID(text)
        if v == nil && l == nil {
            dl.show("That isn't a YouTube video or playlist link")
            return
        }
        paste = PasteRequest(videoID: v, listID: l)
    }
}
