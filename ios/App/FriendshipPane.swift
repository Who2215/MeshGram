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
    func isBound(to identity: NearbyScanner.PublicIdentity) -> Bool { self.identity == identity }
    func invalidateIfStale() { if !identityIsCurrent() { invalidate() } }
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
            status = "Защищённый список прочитан. Отправка заявок ещё не подключена."
        } catch FriendStoreError.notInitialized {
            phase = .needsInitialization
            status = "Защищённый список ещё не создан. Начальная настройка выполняется только с вашего согласия."
        } catch {
            phase = .unavailable
            status = "Защищённый список недоступен или не прошёл проверку. Данные не очищены; создание нового списка запрещено."
        }
    }
    func receiveFriendship(_ packet: SecureMessagePacket, ownIdentity: LocalIdentity, pins: ContactBook) {
        guard identityIsCurrent() else { invalidate(); return }
        guard phase == .ready, let repository else { return }
        do {
            let result = try repository.receiveFriendship(packet, identity: ownIdentity, pins: pins, nearby: false,
                                                         now: Int64(Date().timeIntervalSince1970 * 1000))
            guard identityIsCurrent() else { invalidate(); return }
            switch result.outcome {
            case .changed:
                state = result.state
                status = "Входящие данные дружбы проверены и сохранены. Ответ не отправлялся."
            case .ignored:
                status = "Повторный пакет не изменил список. Ответ не отправлялся."
            case .rejected:
                status = "Входящий пакет не соответствует правилам дружбы. Список не изменён."
            }
        } catch {
            if repository.state == nil {
                state = nil; phase = .unavailable
                status = "Защищённый список недоступен. Данные не сброшены; повторите чтение вручную."
            } else {
                status = "Входящий пакет не применён. Прежний список сохранён."
            }
        }
    }
    func applyOwnerAction(to snapshot: FriendRecord, accepting: Bool) {
        guard identityIsCurrent() else { invalidate(); return }
        guard phase == .ready, let repository,
              state?.records.first(where: { $0.nodeId == snapshot.nodeId }) == snapshot,
              repository.state?.records.first(where: { $0.nodeId == snapshot.nodeId }) == snapshot,
              !snapshot.blocked else {
            status = "Запись изменилась или список недоступен. Откройте подтверждение ещё раз."
            return
        }
        do {
            let result: FriendState.Transition
            if accepting {
                guard snapshot.incomingId != nil, !snapshot.accepted else {
                    status = "Входящая заявка уже недоступна. Список не изменён."; return
                }
                let pins = try ContactPins.load(from: ContactKeychainStore())
                guard let peer = pins.contacts.first(where: {
                    $0.nodeId == snapshot.nodeId && $0.fingerprint == snapshot.fingerprint
                }) else {
                    status = "Подтверждённые ключи не совпали. Согласие не сохранено."; return
                }
                guard identityIsCurrent() else { invalidate(); return }
                result = try repository.accept(peer: peer, now: Int64(Date().timeIntervalSince1970 * 1000))
            } else {
                result = try repository.block(snapshot.nodeId)
            }
            guard identityIsCurrent() else { invalidate(); return }
            switch result.outcome {
            case .changed:
                state = result.state
                status = accepting ? "Согласие сохранено только на этом iPhone. Ответ собеседнику не отправлен."
                    : "Блокировка сохранена на этом iPhone. Сообщение собеседнику не отправлялось."
            case .ignored:
                status = "Действие не изменило список. Ничего не отправлялось."
            case .rejected:
                status = "Действие отклонено: заявка изменилась или устарела. Ничего не отправлялось."
            }
        } catch {
            if repository.state == nil {
                state = nil; phase = .unavailable
                status = "Защищённый список недоступен. Данные не сброшены; повторите чтение вручную."
            } else {
                status = "Действие не подтверждено. Прежние данные не сбрасываются."
            }
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
    @ObservedObject private var model: FriendshipModel
    private enum Confirmation: Identifiable {
        case setup, accept(FriendRecord), block(FriendRecord)
        var id: String {
            switch self {
            case .setup: return "setup"
            case .accept(let record): return "accept|" + record.nodeId + "|" + record.fingerprint
            case .block(let record): return "block|" + record.nodeId + "|" + record.fingerprint
            }
        }
        var title: String {
            switch self {
            case .setup: return "Создать защищённый список друзей?"
            case .accept: return "Принять заявку на этом iPhone?"
            case .block: return "Заблокировать на этом iPhone?"
            }
        }
        var message: String {
            switch self {
            case .setup:
                return "Будут созданы отдельный ключ хранения и пустой закрытый список. Прежние ключи идентичности и контактов не заменяются. Существующий список не будет сброшен."
            case .accept(let record):
                return "\(record.alias)\n\(record.nodeId)\n\nОтпечаток: \(record.fingerprint)\n\nСогласие сохранится только здесь. Ответ собеседнику ещё не отправляется."
            case .block(let record):
                return "\(record.alias)\n\(record.nodeId)\n\nОтпечаток: \(record.fingerprint)\n\nЛокальные заявки будут отменены. Сообщение собеседнику не отправится. Разблокировка пока не подключена."
            }
        }
    }
    @State private var confirmation: Confirmation?
    init(model: FriendshipModel) { self.model = model }
    var body: some View {
        Section("Дружба") {
            Text(model.status).font(.footnote).foregroundStyle(.secondary)
                .accessibilityIdentifier("friendship.status")
            if model.phase == .needsInitialization {
                Button("Настроить хранение дружбы") { confirmation = .setup }
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
                        Text(record.fingerprint).font(.system(.caption2, design: .monospaced)).textSelection(.enabled)
                        if model.phase == .ready && !record.blocked {
                            if record.incomingId != nil && !record.accepted {
                                Button("Принять заявку на этом iPhone") { confirmation = .accept(record) }
                                    .accessibilityIdentifier("friendship.accept")
                            }
                            Button("Заблокировать на этом iPhone", role: .destructive) { confirmation = .block(record) }
                                .accessibilityIdentifier("friendship.block")
                        }
                    }.accessibilityIdentifier("friendship.record")
                }
            }
            Button("Повторить чтение списка") { model.reload() }
                .disabled(model.phase == .reading).accessibilityIdentifier("friendship.reload")
            Text("Подтверждённый ключ сам по себе не означает дружбу. Принятие и блокировка требуют вашего подтверждения и сохраняются только на этом iPhone. Ответы, отправка заявок и чат ещё не подключены.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .alert(confirmation?.title ?? "", isPresented: Binding(
            get: { confirmation != nil }, set: { if !$0 { confirmation = nil } }), presenting: confirmation) { action in
            switch action {
            case .setup:
                Button("Создать список") { model.initializeNew() }
            case .accept(let record):
                Button("Принять локально") { model.applyOwnerAction(to: record, accepting: true) }
            case .block(let record):
                Button("Заблокировать", role: .destructive) { model.applyOwnerAction(to: record, accepting: false) }
            }
            Button("Отмена", role: .cancel) {}
        } message: { action in
            Text(action.message)
        }
    }
}
