import SwiftUI
import WebKit
import UIKit

struct SavedProject: Identifiable, Codable, Hashable {
    var id: UUID = UUID()
    var title: String
    var url: String
}

@MainActor final class ProjectStore: ObservableObject {
    @Published var projects: [SavedProject] = [] {
        didSet { save() }
    }
    private let key = "offset.savedProjects.v1"

    init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let stored = try? JSONDecoder().decode([SavedProject].self, from: data) {
            projects = stored
        }
    }

    func add(title: String, link: String) -> String? {
        guard let url = URL(string: link.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme?.lowercased() == "https",
              url.host != nil else { return "Enter a valid HTTPS project link." }
        guard !projects.contains(where: { $0.url == url.absoluteString }) else {
            return "This project is already saved."
        }
        projects.insert(SavedProject(title: title.isEmpty ? "Untitled project" : title,
                                     url: url.absoluteString), at: 0)
        return nil
    }

    func remove(at offsets: IndexSet) { projects.remove(atOffsets: offsets) }

    private func save() {
        if let data = try? JSONEncoder().encode(projects) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }
}

@main struct OFFSETApp: App {
    @StateObject private var store = ProjectStore()
    var body: some Scene {
        WindowGroup { HomeView().environmentObject(store).preferredColorScheme(.dark) }
    }
}

private enum Theme {
    static let green = Color(red: 0.50, green: 0.95, blue: 0.55)
    static let background = Color(red: 0.075, green: 0.085, blue: 0.085)
}

struct HomeView: View {
    @EnvironmentObject private var store: ProjectStore
    @State private var showingAdd = false

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.background.ignoresSafeArea()
                List {
                    Section {
                        if store.projects.isEmpty {
                            VStack(alignment: .leading, spacing: 10) {
                                Text("Your rushes. Anywhere.").font(.title2.bold())
                                Text("Add a secure OFFSET project link to review proxy clips on your iPhone.")
                                    .foregroundStyle(.secondary)
                                Button("Add your first project") { showingAdd = true }
                                    .tint(Theme.green)
                            }.padding(.vertical, 28)
                        } else {
                            ForEach(store.projects) { project in
                                NavigationLink(value: project) {
                                    HStack(spacing: 14) {
                                        Image(systemName: "film.stack").foregroundStyle(Theme.green)
                                        VStack(alignment: .leading, spacing: 5) {
                                            Text(project.title).font(.headline).foregroundStyle(.white)
                                            Text(URL(string: project.url)?.host ?? project.url)
                                                .font(.caption).foregroundStyle(.secondary)
                                        }
                                        Spacer()
                                        Image(systemName: "chevron.right").foregroundStyle(.secondary)
                                    }.padding(.vertical, 7)
                                }
                            }.onDelete(perform: store.remove)
                        }
                    } header: { Text("Projects") }
                }
                .scrollContentBackground(.hidden)
            }
            .navigationTitle("OFFSET.")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showingAdd = true } label: {
                        Image(systemName: "plus").foregroundStyle(Theme.green)
                    }.accessibilityLabel("Add project")
                }
            }
            .sheet(isPresented: $showingAdd) { AddProjectView() }
            .navigationDestination(for: SavedProject.self) { project in
                ReviewView(project: project)
            }
        }
        .tint(Theme.green)
    }
}

struct AddProjectView: View {
    @EnvironmentObject private var store: ProjectStore
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var link = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Project") {
                    TextField("Project name", text: $title)
                    TextField("HTTPS share link", text: $link)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                }
                Section {
                    Text("Paste the secure project link from OFFSET. PIN-protected projects will ask for their PIN in the review page.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle("Add project")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        error = store.add(title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                                          link: link)
                        if error == nil { dismiss() }
                    }.disabled(link.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

struct ReviewView: View {
    let project: SavedProject
    @State private var refreshCount = 0

    var body: some View {
        ReviewWebView(url: URL(string: project.url)!, refreshCount: refreshCount)
            .background(Theme.background)
            .navigationTitle(project.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        refreshCount += 1
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel("Refresh project")

                    ShareLink(item: URL(string: project.url)!) {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
            }
    }
}

struct ReviewWebView: UIViewRepresentable {
    let url: URL
    let refreshCount: Int

    @MainActor final class Coordinator: NSObject, WKScriptMessageHandlerWithReply {
        weak var webView: WKWebView?
        let allowedHost: String?
        var lastRefreshCount: Int

        init(refreshCount: Int, allowedHost: String?) {
            lastRefreshCount = refreshCount
            self.allowedHost = allowedHost
            super.init()
        }

        func userContentController(_ userContentController: WKUserContentController,
                                   didReceive message: WKScriptMessage,
                                   replyHandler: @escaping (Any?, String?) -> Void) {
            guard message.frameInfo.isMainFrame,
                  message.frameInfo.securityOrigin.protocol == "https",
                  message.frameInfo.securityOrigin.host == allowedHost,
                  let body = message.body as? [String: String],
                  let encoded = body["data"], encoded.count <= 40_000_000,
                  let data = Data(base64Encoded: encoded),
                  data.starts(with: Data("%PDF".utf8)),
                  let view = webView,
                  var presenter = view.window?.rootViewController else {
                replyHandler(nil, "Unable to save this PDF. Try fewer shots or open in Safari.")
                return
            }
            while let presented = presenter.presentedViewController { presenter = presented }
            let rawName = (body["name"] ?? "OFFSET-plan.pdf") as NSString
            var name = rawName.lastPathComponent.replacingOccurrences(
                of: "[^A-Za-z0-9._-]", with: "-", options: .regularExpression)
            if !name.lowercased().hasSuffix(".pdf") { name += ".pdf" }
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
            let file = directory.appendingPathComponent(name)
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                try data.write(to: file, options: .atomic)
            } catch {
                try? FileManager.default.removeItem(at: directory)
                replyHandler(nil, "Could not prepare the PDF for sharing.")
                return
            }
            let sheet = UIActivityViewController(activityItems: [file], applicationActivities: nil)
            sheet.popoverPresentationController?.sourceView = view
            sheet.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)
            sheet.popoverPresentationController?.permittedArrowDirections = []
            sheet.completionWithItemsHandler = { _, _, _, _ in try? FileManager.default.removeItem(at: directory) }
            presenter.present(sheet, animated: true) { replyHandler(["presented": true], nil) }
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(refreshCount: refreshCount, allowedHost: url.host)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.userContentController.addScriptMessageHandler(context.coordinator, contentWorld: .page, name: "offsetPDF")
        let view = WKWebView(frame: .zero, configuration: configuration)
        context.coordinator.webView = view
        view.allowsBackForwardNavigationGestures = true
        view.scrollView.contentInsetAdjustmentBehavior = .never
        view.load(URLRequest(url: url))
        return view
    }
    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "offsetPDF", contentWorld: .page)
        coordinator.webView = nil
    }
    func updateUIView(_ uiView: WKWebView, context: Context) {
        guard context.coordinator.lastRefreshCount != refreshCount else { return }
        context.coordinator.lastRefreshCount = refreshCount
        if uiView.reloadFromOrigin() == nil {
            uiView.load(URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData))
        }
    }
}
