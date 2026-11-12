import Combine
import Foundation
import MeshGramCore
import SwiftUI

@MainActor
final class FriendshipModel: ObservableObject {
    enum Phase: Equatable { case reading, needsInitialization, ready, unavailable }
    @Published private(set) var phase: Phase = .reading
    @Published private(set) var state: FriendState?
    @Published private(set) var status = "Читаем защищённый список друзей…"
    private let identity: NearbyScanner.PublicIdentity
    private let identityIsCurrent: @MainActor () -> Bool
    private var repository: FriendStateRepository?
    private var parent: URL?

    init(identity: NearbyScanner.PublicIdentity, identityIsCurrent: @escaping @MainActor () -> Bool) {
        self.identity = identity; self.identityIsCurrent = identityIsCurrent
        reload()
    }
    private func invalidate() {
        repository = nil; parent = nil; state = nil; phase = .unavailable
        status = "Идентичность недоступна или изменилась. Прежние данные не изменяются."
    }
    func reload() {
        guard identityIsCurrent() else { invalidate(); return }
        phase = .reading; state = nil
        do {
            if repository == nil {
                guard let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
                    throw FriendStoreError.invalidStorage
                }
                let keys = try KeychainFriendStateKey(localNodeId: identity.nodeId, ownFingerprint: identity.fingerprint)
                let blobs = try FileFriendStateBlob(directory: support.appendingPathComponent("friendstate-v1", isDirectory: true))
                repository = FriendStateRepository(keys: keys, blobs: blobs, localNodeId: identity.nodeId, ownFingerprint: identity.fingerprint)
                parent = support
            }
            guard let repository else { throw FriendStoreError.invalidStorage }
            let restored = try repository.load()
            guard identityIsCurrent() else { invalidate(); return }
            state = restored; phase = .ready
            status = "Защищённый список прочитан. Обмен заявками ещё не подключён."
        } catch FriendStoreError.notInitialized {
            phase = .needsInitialization
            status = "Защищённый список ещё не создан. Начальная настройка выполняется только с вашего согласия."
        } catch {
            phase = .unavailable
            status = "Защищённый список недоступен или не прошёл проверку. Данные не очищены; создание нового списка запрещено."
        }
    }
    func initializeNew() {
        guard identityIsCurrent() else { invalidate(); return }
        guard phase == .needsInitialization, let repository, let parent else { return }
        phase = .reading; state = nil
        do {
            // Prepare the app-owned parent before adding a new key; no file writes occur in reload.
            try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.complete])
            let attributes = try FileManager.default.attributesOfItem(atPath: parent.path)
            let home = URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true).resolvingSymlinksInPath().path
            guard attributes[.type] as? FileAttributeType == .typeDirectory,
                  parent.resolvingSymlinksInPath().path.hasPrefix(home + "/"),
                  FileManager.default.isWritableFile(atPath: parent.path) else { throw FriendStoreError.invalidStorage }
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: parent.path)
            let permissions = try FileManager.default.attributesOfItem(atPath: parent.path)[.posixPermissions] as? NSNumber
            guard permissions?.intValue == 0o700 else { throw FriendStoreError.invalidStorage }
            guard identityIsCurrent() else { invalidate(); return }
            let created = try repository.initializeNew()
            state = created; phase = .ready
            status = "Защищённый список создан и проверен. Ключи идентичности и контактов не менялись."
        } catch {
            phase = .unavailable
            status = "Настройка не подтверждена. Данные и ключи не сбрасываются. Повторите чтение; автоматическое восстановление выключено."
        }
    }
}

@MainActor
struct FriendshipPane: View {
    @StateObject private var model: FriendshipModel
    @State private var confirmSetup = false
    init(identity: NearbyScanner.PublicIdentity, identityIsCurrent: @escaping @MainActor () -> Bool) {
        _model = StateObject(wrappedValue: FriendshipModel(identity: identity, identityIsCurrent: identityIsCurrent))
    }
    var body: some View {
        Section("Дружба") {
            Text(model.status).font(.footnote).foregroundStyle(.secondary)
                .accessibilityIdentifier("friendship.status")
            if model.phase == .needsInitialization {
                Button("Настроить хранение дружбы") { confirmSetup = true }
                    .accessibilityIdentifier("friendship.setup")
            }
            if let state = model.state {
                if state.records.isEmpty { Text("Список друзей пока пуст") }
                ForEach(state.records, id: \.nodeId) { record in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(record.alias).font(.headline)
                        Text(record.blocked ? "Заблокирован" : record.accepted ? "Друг" : "Ожидает подтверждения")
                            .font(.caption).foregroundStyle(.secondary)
                        Text(record.nodeId).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                    }.accessibilityIdentifier("friendship.record")
                }
            }
            Button("Повторить чтение списка") { model.reload() }
                .disabled(model.phase == .reading).accessibilityIdentifier("friendship.reload")
            Text("Подтверждённый ключ сам по себе не означает дружбу. Приём заявок, чат и отправка сообщений ещё не подключены.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .alert("Создать защищённый список друзей?", isPresented: $confirmSetup) {
            Button("Создать список") { model.initializeNew() }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Будут созданы отдельный ключ хранения и пустой закрытый список. Прежние ключи идентичности и контактов не заменяются. Существующий список не будет сброшен.")
        }
    }
}
