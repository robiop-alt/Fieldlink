import SwiftUI
import WebKit

struct SavedProject: Identifiable, Codable, Equatable {
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
    @State private var selected: SavedProject?

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
                                Button {
                                    selected = project
                                } label: {
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
            .navigationDestination(item: $selected) { project in
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
    var body: some View {
        ReviewWebView(url: URL(string: project.url)!)
            .background(Theme.background)
            .navigationTitle(project.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ShareLink(item: URL(string: project.url)!) {
                    Image(systemName: "square.and.arrow.up")
                }
            }
    }
}

struct ReviewWebView: UIViewRepresentable {
    let url: URL
    func makeUIView(context: Context) -> WKWebView {
        let view = WKWebView()
        view.allowsBackForwardNavigationGestures = true
        view.scrollView.contentInsetAdjustmentBehavior = .never
        view.load(URLRequest(url: url))
        return view
    }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
