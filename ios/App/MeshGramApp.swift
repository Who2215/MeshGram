import SwiftUI
import MeshGramCore

@main
struct MeshGramApp: App {
    var body: some Scene { WindowGroup { NearbyScreen() } }
}

private struct NearbyScreen: View {
    @StateObject private var scanner = NearbyScanner()
    @Environment(\.scenePhase) private var scenePhase
    @State private var pendingConfirmation: ContactConfirmationGate.Candidate?
    private let accent = Color(red: 34 / 255, green: 247 / 255, blue: 234 / 255)
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label("Испытательная версия для iPhone", systemImage: "antenna.radiowaves.left.and.right")
                        .font(.headline).foregroundStyle(accent)
                    Text(scanner.status)
                    Text("Доступны поиск, проверка подписи и локальное подтверждение ключей. Входящие пакеты дружбы проходят отдельную проверку. Отправка заявок, чат, файлы и работа в фоне пока не подключены.")
                        .font(.footnote).foregroundStyle(.secondary)
                    Button("Найти устройства рядом") { scanner.start() }
                        .accessibilityIdentifier("nearby.start")
                    Button("Остановить поиск", role: .cancel) { scanner.stop() }
                }
                Section("Этот iPhone") {
                    if let identity = scanner.publicIdentity {
                        Text("Идентификатор узла").font(.caption).foregroundStyle(.secondary)
                        Text(identity.nodeId)
                            .font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                            .accessibilityIdentifier("identity.nodeId")
                        Text("Отпечаток публичных ключей").font(.caption).foregroundStyle(.secondary)
                        Text(identity.fingerprint)
                            .font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                            .accessibilityIdentifier("identity.fingerprint")
                        Text("Это публичные данные. Сравните оба значения после повторного запуска приложения.")
                            .font(.footnote).foregroundStyle(.secondary)
                    } else {
                        Text("Идентичность недоступна. Проверьте защищённое хранилище; новые ключи вместо прежних не создаются.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Section("Подтверждённые ключи") {
                    Text(scanner.contactStorageStatus).font(.footnote).foregroundStyle(.secondary)
                    if scanner.contactsAvailable {
                        if scanner.confirmedContacts.isEmpty { Text("Подтверждённых контактов пока нет") }
                        ForEach(scanner.confirmedContacts) { contact in
                            VStack(alignment: .leading, spacing: 6) {
                                Text(contact.nodeId).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                                Text(contact.fingerprint).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                            }.accessibilityIdentifier("contacts.saved")
                        }
                    } else {
                        Button("Повторить чтение контактов") { scanner.reloadContacts() }
                    }
                }
                if let identity = scanner.publicIdentity, let model = scanner.friendship(for: identity) {
                    FriendshipPane(model: model)
                        .id(identity.nodeId + "|" + identity.fingerprint)
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
                                if let nodeId = peer.nodeId {
                                    Text("Идентификатор узла").font(.caption)
                                    Text(nodeId).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                                }
                                Text(fingerprint).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                                Text("Сравните отпечаток на телефоне собеседника. Проверенная подпись не означает добавление в друзья.")
                                    .font(.caption).foregroundStyle(.secondary)
                                if let match = scanner.contactMatch(for: peer.id) {
                                    switch match {
                                    case .confirmed:
                                        Label("Ключи подтверждены на этом iPhone", systemImage: "checkmark.shield")
                                            .font(.footnote)
                                    case .changed:
                                        Label("Ключи этого контакта изменились. Автоматическая замена запрещена.", systemImage: "exclamationmark.shield")
                                            .font(.footnote).foregroundStyle(.orange)
                                    case .unconfirmed:
                                        Button("Сверить и подтвердить ключи") {
                                            pendingConfirmation = scanner.confirmationCandidate(for: peer.id)
                                        }.accessibilityIdentifier("contacts.confirm")
                                    }
                                }
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
        .alert(item: $pendingConfirmation) { candidate in
            Alert(title: Text("Сравните ключи на двух телефонах"),
                message: Text("Идентификатор: \(candidate.nodeId)\n\nОтпечаток: \(candidate.fingerprint)\n\nПодтвердите только после полного совпадения на телефоне собеседника. Это сохранит ключи только на этом iPhone, без автоматической дружбы или отправки сообщения."),
                primaryButton: .default(Text("Ключи совпадают")) { scanner.confirmContact(candidate) },
                secondaryButton: .cancel(Text("Отмена")))
        }
        .onChange(of: scenePhase) { phase in
            if phase != .active { pendingConfirmation = nil; scanner.stop() }
        }
    }
}
