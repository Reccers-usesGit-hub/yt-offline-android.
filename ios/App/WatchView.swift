import SwiftUI

struct WatchView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var sharer: Sharer
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var hSize

    @StateObject private var model = PlayerModel()
    @State private var current: String
    @State private var list: String?
    @State private var showDescription = false
    @State private var confirmRemove = false
    @State private var listOpen = true

    init(id: String, listID: String?) {
        _current = State(initialValue: id)
        _list = State(initialValue: listID)
    }

    private var video: Video? { store.get(current) }

    private var listItems: [Video] {
        guard let list else { return [] }
        return store.videos
            .filter { $0.playlists.contains { $0.id == list } }
            .sorted { idx($0) < idx($1) }
    }

    private func idx(_ v: Video) -> Int { v.playlists.first { $0.id == list }?.index ?? .max }

    private var others: [Video] {
        let inList = Set(listItems.map(\.id))
        return store.videos.filter { $0.id != current && !inList.contains($0.id) }.sorted { $0.savedAt > $1.savedAt }
    }

    var body: some View {
        Group {
            if let v = video {
                if hSize == .regular {
                    HStack(alignment: .top, spacing: 24) {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 0) {
                                player.lifted(16).padding(.top, 8)
                                info(v)
                            }
                        }
                        ScrollView { side.padding(.top, 8) }
                            .frame(width: 400)
                    }
                    .padding(.horizontal, 20)
                } else {
                    VStack(spacing: 0) {
                        player
                        ScrollView {
                            VStack(alignment: .leading, spacing: 0) {
                                info(v)
                                side.padding(.horizontal, 10)
                            }
                        }
                    }
                }
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "trash").font(.largeTitle).foregroundStyle(.secondary)
                    Text("This video was removed").font(.headline)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task(id: current) {
            if let v = video {
                model.onEnd = { playNext() }
                await model.load(v)
            }
        }
        .onDisappear { model.stop() }
        .sheet(isPresented: $showDescription) {
            if let v = video { DescriptionSheet(v: v) }
        }
        .confirmationDialog("Remove from downloads?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                let id = current
                model.stop()
                dismiss()
                store.remove([id])
            }
        }
    }

    private var player: some View {
        PlayerView(player: model.player)
            .aspectRatio(16 / 9, contentMode: .fit)
            .background(Color.black)
    }

    private func playNext() {
        guard let i = listItems.firstIndex(where: { $0.id == current }), i + 1 < listItems.count else { return }
        current = listItems[i + 1].id
    }

    // MARK: details under the player

    @ViewBuilder
    private func info(_ v: Video) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 6) {
                Text(v.title).font(.title3.weight(.semibold)).lineLimit(3)
                Button { showDescription = true } label: {
                    HStack(spacing: 6) {
                        Text([Fmt.views(v.views), v.publishedText].filter { !$0.isEmpty }.joined(separator: "  "))
                        Text("…more").fontWeight(.semibold).foregroundStyle(.primary)
                    }
                    .font(.caption).foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }

            HStack(spacing: 12) {
                Avatar(id: v.id, name: v.channel, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(v.channel).font(.subheadline.weight(.semibold)).lineLimit(1)
                    if !v.subs.isEmpty { Text(v.subs).font(.caption).foregroundStyle(.secondary) }
                }
                Spacer()
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    likeDislike(v)
                    ShareLink(item: URL(string: "https://youtu.be/\(v.id)")!) {
                        Label("Share link", systemImage: "arrowshape.turn.up.right")
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 14).padding(.vertical, 9)
                    }
                    .buttonStyle(.plain)
                    .glass(Capsule(), interactive: true)
                    GlassPill(title: "Share file", systemImage: "square.and.arrow.up") { sharer.share(v) }
                    GlassPill(title: "Downloaded · \(v.height > 0 ? "\(v.height)p" : Fmt.bytes(v.size))", systemImage: "checkmark.circle") {}
                    GlassPill(title: "Remove", systemImage: "trash") { confirmRemove = true }
                }
                .padding(.vertical, 4)
                .padding(.horizontal, 2)
            }

            Button { showDescription = true } label: {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Description").font(.subheadline.weight(.semibold))
                    Text(v.description.isEmpty ? "No description." : v.description)
                        .font(.subheadline).foregroundStyle(.secondary).lineLimit(3)
                        .multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(.secondarySystemBackground)))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, hSize == .regular ? 0 : 14)
        .padding(.vertical, 14)
    }

    private func likeDislike(_ v: Video) -> some View {
        HStack(spacing: 0) {
            Button {
                store.update(v.id) { $0.rating = $0.rating == "like" ? nil : "like" }
            } label: {
                Label(v.likes.isEmpty ? "Like" : v.likes, systemImage: v.rating == "like" ? "hand.thumbsup.fill" : "hand.thumbsup")
                    .padding(.leading, 14).padding(.trailing, 10).padding(.vertical, 9)
            }
            Divider().frame(height: 20)
            Button {
                store.update(v.id) { $0.rating = $0.rating == "dislike" ? nil : "dislike" }
            } label: {
                Image(systemName: v.rating == "dislike" ? "hand.thumbsdown.fill" : "hand.thumbsdown")
                    .padding(.leading, 10).padding(.trailing, 14).padding(.vertical, 9)
            }
        }
        .font(.subheadline.weight(.medium))
        .buttonStyle(.plain)
        .glass(Capsule(), interactive: true)
    }

    // MARK: playlist + more downloads

    @ViewBuilder
    private var side: some View {
        VStack(alignment: .leading, spacing: 12) {
            if list != nil, !listItems.isEmpty {
                let pos = (listItems.firstIndex { $0.id == current } ?? 0) + 1
                VStack(alignment: .leading, spacing: 4) {
                    Button { withAnimation(.snappy) { listOpen.toggle() } } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(listItems.first?.playlists.first { $0.id == list }?.title ?? "Playlist")
                                    .font(.subheadline.weight(.semibold)).lineLimit(1)
                                Text("\(pos) / \(listItems.count)").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: listOpen ? "chevron.up" : "chevron.down").font(.subheadline.weight(.semibold))
                        }
                        .padding(14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    if listOpen {
                        ForEach(Array(listItems.enumerated()), id: \.element.id) { i, o in
                            VideoRow(v: o, watched: store.watched(o), index: i + 1, current: o.id == current, menu: rowMenu(o)) {
                                if o.id != current { current = o.id }
                            }
                        }
                        .padding(.horizontal, 6)
                        .padding(.bottom, 6)
                    }
                }
                .glass(RoundedRectangle(cornerRadius: 18, style: .continuous))
            }

            if !others.isEmpty {
                Text("More downloads").font(.headline).padding(.top, 6).padding(.horizontal, 4)
                ForEach(others) { o in
                    VideoRow(v: o, watched: store.watched(o), menu: rowMenu(o)) {
                        list = nil
                        current = o.id
                    }
                }
            }
        }
        .padding(.bottom, 24)
    }

    private func rowMenu(_ o: Video) -> [MenuAction] {
        [
            MenuAction(title: "Share video file", systemImage: "square.and.arrow.up") { sharer.share(o) },
            MenuAction(title: "Remove from downloads", systemImage: "trash", role: .destructive) {
                if o.id == current { confirmRemove = true } else { store.remove([o.id]) }
            },
        ]
    }
}

struct DescriptionSheet: View {
    let v: Video
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(v.title).font(.headline)
                    HStack(spacing: 28) {
                        stat(v.likes.isEmpty ? "—" : v.likes, "Likes")
                        stat(v.views > 0 ? v.views.formatted() : "—", "Views")
                        stat(v.publishedText.isEmpty ? "—" : v.publishedText, "Uploaded")
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .glass(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    Text(v.description.isEmpty ? "No description." : v.description)
                        .font(.subheadline)
                        .textSelection(.enabled)
                }
                .padding(18)
            }
            .navigationTitle("Description")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.subheadline.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }
}
