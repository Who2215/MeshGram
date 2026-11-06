import SwiftUI

@main
struct MeshGramApp: App {
    var body: some Scene { WindowGroup { NearbyScreen() } }
}

private struct NearbyScreen: View {
    @StateObject private var scanner = NearbyScanner()
    @Environment(\.scenePhase) private var scenePhase
    private let accent = Color(red: 34 / 255, green: 247 / 255, blue: 234 / 255)
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label("Испытательная версия для iPhone", systemImage: "antenna.radiowaves.left.and.right")
                        .font(.headline).foregroundStyle(accent)
                    Text(scanner.status)
                    Text("Сейчас доступны обнаружение и проверка подписи. Чат, отправка файлов и работа в фоне пока не подключены.")
                        .font(.footnote).foregroundStyle(.secondary)
                    Button("Найти устройства рядом") { scanner.start() }
                        .accessibilityIdentifier("nearby.start")
                    Button("Остановить поиск", role: .cancel) { scanner.stop() }
                }
                Section("Устройства рядом") {
                    if scanner.peers.isEmpty {
                        Text("Включите поиск MeshGram на Samsung и оставьте оба приложения открытыми.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(scanner.peers) { peer in
                        VStack(alignment: .leading, spacing: 8) {
                            Label(peer.name, systemImage: peer.fingerprint == nil ? "antenna.radiowaves.left.and.right" : "checkmark.shield")
                                .font(.headline)
                            Text(peer.detail).font(.footnote).foregroundStyle(.secondary)
                            if let fingerprint = peer.fingerprint {
                                Text(fingerprint).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                                Text("Сравните отпечаток на телефоне собеседника. Проверенная подпись не означает добавление в друзья.")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }.padding(.vertical, 8)
                    }
                }
            }
            .navigationTitle("MeshGram")
            .scrollContentBackground(.hidden)
            .background(Color(red: 5 / 255, green: 7 / 255, blue: 13 / 255))
            .tint(accent)
        }
        .preferredColorScheme(.dark)
        .onChange(of: scenePhase) { phase in if phase != .active { scanner.stop() } }
    }
}
