import SwiftUI

struct MenuAction: Identifiable {
    var title: String
    var systemImage: String
    var role: ButtonRole? = nil
    var action: () -> Void
    var id: String { title }
}

/// The ⋮ button.
struct DotsMenu: View {
    let items: [MenuAction]
    var body: some View {
        Menu {
            ForEach(items) { m in
                Button(role: m.role, action: m.action) { Label(m.title, systemImage: m.systemImage) }
            }
        } label: {
            Image(systemName: "ellipsis")
                .rotationEffect(.degrees(90))
                .font(.body.weight(.semibold))
                .foregroundStyle(.primary)
                .frame(width: 32, height: 32)
                .contentShape(Rectangle())
        }
        .accessibilityLabel("More")
    }
}

/// 16:9 thumbnail with duration badge and the red "watched" bar.
struct Thumb: View {
    let id: String
    var duration: Double = 0
    var watched: Double = 0
    var radius: CGFloat = 12

    var body: some View {
        Color.clear
            .aspectRatio(16 / 9, contentMode: .fit)
            .overlay(LocalImage(url: Store.dir(id).appendingPathComponent("thumb.jpg")))
            .overlay(alignment: .bottomTrailing) {
                if duration > 0 { GlassBadge(text: Fmt.duration(duration)).padding(6) }
            }
            .overlay(alignment: .bottom) {
                if watched > 0 {
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Rectangle().fill(.white.opacity(0.35))
                            Rectangle().fill(Color.red).frame(width: g.size.width * watched)
                        }
                    }
                    .frame(height: 4)
                }
            }
            .lifted(radius)
    }
}

struct Avatar: View {
    let id: String
    let name: String
    var size: CGFloat = 36

    var body: some View {
        let file = Store.dir(id).appendingPathComponent("avatar.jpg")
        Group {
            if let img = ImageCache.image(file) {
                Image(uiImage: img).resizable().scaledToFill()
            } else {
                ZStack {
                    Circle().fill(Color(.tertiarySystemFill))
                    Text(String(name.prefix(1)).uppercased()).font(.subheadline.weight(.semibold))
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }
}

struct VideoCard: View {
    let v: Video
    let watched: Double
    let menu: [MenuAction]
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Thumb(id: v.id, duration: v.duration, watched: watched)
                .onTapGesture(perform: onOpen)
            HStack(alignment: .top, spacing: 12) {
                Avatar(id: v.id, name: v.channel)
                VStack(alignment: .leading, spacing: 3) {
                    Text(v.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                    Text(metaLine(v)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture(perform: onOpen)
                DotsMenu(items: menu)
            }
        }
    }
}

func metaLine(_ v: Video) -> String {
    [v.channel, Fmt.views(v.views), v.publishedText].filter { !$0.isEmpty }.joined(separator: " · ")
}

/// YouTube-style stacked card for a downloaded playlist.
struct PlaylistCard: View {
    let title: String
    let videos: [Video]
    let menu: [MenuAction]
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ZStack(alignment: .top) {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color(.systemGray3))
                    .padding(.horizontal, 22)
                    .offset(y: -9)
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .fill(Color(.systemGray2))
                    .padding(.horizontal, 11)
                    .offset(y: -4.5)
                Thumb(id: videos.first?.id ?? "")
                    .overlay(alignment: .bottomTrailing) {
                        GlassBadge(text: "\(videos.count) video\(videos.count == 1 ? "" : "s")", systemImage: "list.bullet")
                            .padding(6)
                    }
            }
            .padding(.top, 9)
            .onTapGesture(perform: onOpen)
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.subheadline.weight(.semibold)).lineLimit(2)
                    Text("Playlist · \(videos.first?.channel ?? "")").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    Text("View full playlist").font(.caption.weight(.medium)).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture(perform: onOpen)
                DotsMenu(items: menu)
            }
        }
    }
}

/// Compact row used inside a playlist and in "Up next".
struct VideoRow: View {
    let v: Video
    let watched: Double
    var index: Int? = nil
    var current = false
    let menu: [MenuAction]
    let onOpen: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            if let index {
                Group {
                    if current { Image(systemName: "play.fill").font(.caption2) } else { Text("\(index)") }
                }
                .font(.caption).foregroundStyle(.secondary)
                .frame(width: 22)
                .padding(.top, 26)
            }
            Thumb(id: v.id, duration: v.duration, watched: watched, radius: 9)
                .frame(width: 150)
            VStack(alignment: .leading, spacing: 3) {
                Text(v.title).font(.subheadline.weight(.medium)).lineLimit(2)
                Text(v.channel).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            DotsMenu(items: menu)
        }
        .padding(.vertical, 4)
        .padding(.horizontal, 6)
        .background(current ? RoundedRectangle(cornerRadius: 12).fill(Color(.tertiarySystemFill)) : nil)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
    }
}

/// A video that's still downloading (or failed).
struct JobCard: View {
    let job: DlJob
    @EnvironmentObject var dl: Downloader

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Color.clear
                .aspectRatio(16 / 9, contentMode: .fit)
                .overlay(
                    AsyncImage(url: YT.thumbURL(job.id)) { $0.resizable().scaledToFill() }
                        placeholder: { Rectangle().fill(Color(.secondarySystemFill)) }
                )
                .overlay(Color.black.opacity(0.45))
                .overlay {
                    if job.failed != nil {
                        Image(systemName: "exclamationmark.triangle.fill").font(.title).foregroundStyle(.white)
                    } else {
                        VStack(spacing: 6) {
                            Text(job.stage.isEmpty ? "\(Int(job.progress * 100))%" : job.stage)
                                .font(.subheadline.weight(.semibold).monospacedDigit())
                                .foregroundStyle(.white)
                            if job.total > 0 {
                                Text("\(Fmt.bytes(job.done)) of \(Fmt.bytes(job.total))")
                                    .font(.caption.monospacedDigit()).foregroundStyle(.white.opacity(0.8))
                            }
                        }
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .glass(Capsule(), tint: .black.opacity(0.3))
                    }
                }
                .overlay(alignment: .bottom) {
                    if job.failed == nil {
                        ProgressView(value: job.progress).tint(.red).padding(10)
                    }
                }
                .lifted()
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(job.title.isEmpty ? "Getting video…" : job.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                    Text(job.failed ?? (job.running ? "Downloading" : "Waiting"))
                        .font(.caption).foregroundStyle(job.failed != nil ? Color.red : Color.secondary).lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                DotsMenu(items: (job.failed != nil
                                 ? [MenuAction(title: "Try again", systemImage: "arrow.clockwise") { dl.retry(job.id) }]
                                 : [])
                         + [MenuAction(title: job.failed != nil ? "Remove" : "Cancel download", systemImage: "xmark", role: .destructive) { dl.cancel(job.id) }])
            }
        }
    }
}

/// Several videos of one playlist downloading.
struct PlaylistJobCard: View {
    let title: String
    let jobs: [DlJob]
    @EnvironmentObject var dl: Downloader

    var body: some View {
        let running = jobs.first { $0.running } ?? jobs[0]
        VStack(alignment: .leading, spacing: 10) {
            JobCard(job: running).environmentObject(dl)
            Text("“\(title)” · \(jobs.count) left to download")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// Floating status message at the top.
struct Banner: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.subheadline.weight(.medium))
            .multilineTextAlignment(.center)
            .padding(.horizontal, 18).padding(.vertical, 11)
            .glass(Capsule())
            .padding(.horizontal, 24)
            .transition(.move(edge: .top).combined(with: .opacity))
    }
}
