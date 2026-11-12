import CoreBluetooth
import Combine
import Foundation
import MeshGramCore

final class NearbyScanner: NSObject, ObservableObject, CBCentralManagerDelegate {
    struct Peer: Identifiable {
        let id: UUID
        var name: String
        var fingerprint: String?
        var nodeId: String?
        var verificationDetail: String
        var sendingDetail: String?
        var detail: String { [verificationDetail, sendingDetail].compactMap { $0 }.joined(separator: "\n") }
    }
    struct PublicIdentity {
        let nodeId: String
        let fingerprint: String
    }
    // A separate delegate binds peripheral callbacks to a connection, not just its UUID.
    private final class Link: NSObject, CBPeripheralDelegate {
        let peripheral: CBPeripheral
        weak var owner: NearbyScanner?
        var notify: CBCharacteristic?
        var write: CBCharacteristic?
        var notificationRequested = false
        var helloStarted = false
        var queue: HelloWriteQueue?
        var timeout: DispatchWorkItem?
        var timeoutToken: UUID?
        init(_ peripheral: CBPeripheral, owner: NearbyScanner) {
            self.peripheral = peripheral
            self.owner = owner
            super.init()
        }
        func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
            owner?.servicesDiscovered(self, error: error)
        }
        func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
            owner?.characteristicsDiscovered(self, service: service, error: error)
        }
        func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
            owner?.notificationChanged(self, characteristic: characteristic, error: error)
        }
        func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
            owner?.writeResponded(self, characteristic: characteristic, error: error)
        }
        func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
            owner?.received(self, characteristic: characteristic, error: error)
        }
    }
    @Published private(set) var publicIdentity: PublicIdentity?
    @Published private(set) var peers: [Peer] = []
    @Published private(set) var status = "Поиск ещё не запущен"
    @Published private(set) var confirmedContacts: [ConfirmedContact] = []
    @Published private(set) var contactsAvailable = false
    @Published private(set) var contactStorageStatus = "Контакты ещё не прочитаны"
    private var contactBook: ContactBook?
    private var confirmationGate = ContactConfirmationGate()
    private var central: CBCentralManager?
    private var sessionGate = HelloSessionGate()
    private var connections: [UUID: Link] = [:]
    private var attempted: Set<UUID> = []
    private var assemblers: [UUID: FrameAssembler] = [:]
    private var searchIdentity: LocalIdentity?
    private var started = false
    private var now: TimeInterval { ProcessInfo.processInfo.systemUptime }

    override init() {
        super.init()
        _ = loadIdentity()
        reloadContacts()
    }
    func reloadContacts() {
        do {
            let book = try ContactPins.load(from: ContactKeychainStore())
            contactBook = book
            confirmedContacts = book.contacts
            contactsAvailable = true
            contactStorageStatus = "Подтверждение ключей хранится только на этом iPhone. Это ещё не дружба в сети."
        } catch {
            contactBook = nil
            confirmedContacts = []
            contactsAvailable = false
            contactStorageStatus = "Защищённое хранилище контактов недоступно. Подтверждение выключено; прежние записи не заменяются."
        }
    }
    func confirmationCandidate(for peerId: UUID) -> ContactConfirmationGate.Candidate? {
        guard contactsAvailable, started, let link = connections[peerId], current(link) else { return nil }
        return confirmationGate.candidate(for: peerId)
    }
    func contactMatch(for peerId: UUID) -> ContactBook.Match? {
        guard let candidate = confirmationCandidate(for: peerId), let book = contactBook else { return nil }
        return book.match(nodeId: candidate.nodeId, fingerprint: candidate.fingerprint)
    }
    func confirmContact(_ candidate: ContactConfirmationGate.Candidate) {
        guard confirmationCandidate(for: candidate.peerId) == candidate else {
            status = "Данные узла изменились или поиск остановлен. Сверьте ключи ещё раз."
            return
        }
        do {
            let book = try ContactPins.confirm(candidate, gate: confirmationGate, storage: ContactKeychainStore(),
                confirmedAtMs: Int64(Date().timeIntervalSince1970 * 1000))
            contactBook = book
            confirmedContacts = book.contacts
            contactsAvailable = true
            status = "Ключи контакта сохранены на этом iPhone. Отправка сообщений ещё не подключена."
        } catch ContactPinError.changedKey {
            status = "Ключи контакта изменились. Прежние ключи сохранены, замена запрещена."
        } catch ContactPinError.staleConfirmation {
            status = "Подтверждение устарело. Сверьте ключи ещё раз."
        } catch ContactPinError.capacity {
            status = "Список подтверждённых контактов заполнен. Прежние записи сохранены."
        } catch {
            contactBook = nil
            confirmedContacts = []
            contactsAvailable = false
            contactStorageStatus = "Не удалось проверить сохранение контакта. Подтверждение выключено; данные не очищаются."
        }
    }
    private func loadIdentity() -> LocalIdentity? {
        do {
            let identity = try IdentityStore.loadOrCreate()
            publicIdentity = PublicIdentity(nodeId: identity.nodeId, fingerprint: identity.publicFingerprint)
            return identity
        } catch {
            publicIdentity = nil
            status = "Защищённое хранилище недоступно. Поиск не запущен."
            return nil
        }
    }
    func start() {
        guard !started, let identity = loadIdentity() else { return }
        searchIdentity = identity
        reloadContacts()
        confirmationGate.invalidateAll()
        peers.removeAll()
        attempted.removeAll()
        started = true
        let manager = CBCentralManager(delegate: self, queue: .main)
        central = manager
        sessionGate.activate(manager)
    }
    func stop() {
        started = false
        sessionGate.invalidate()
        confirmationGate.invalidateAll()
        central?.stopScan()
        for link in Array(connections.values) { close(link, reason: "Передача остановлена") }
        central?.delegate = nil
        central = nil
        assemblers.removeAll()
        searchIdentity = nil
        status = "Поиск остановлен"
    }
    private func current(_ link: Link) -> Bool {
        started && central?.state == .poweredOn && connections[link.peripheral.identifier] === link
    }
    private func beginScan() {
        central?.scanForPeripherals(withServices: [CBUUID(string: MeshWire.service)], options: nil)
        status = "Ищем MeshGram рядом"
    }
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard sessionGate.accepts(central) else { return }
        if central.state == .poweredOn { if started { beginScan() }; return }
        stop()
        switch central.state {
        case .unauthorized: status = "Разрешите доступ к блютузу в настройках iPhone"
        case .poweredOff: status = "Блютуз выключен"
        default: status = "Блютуз пока недоступен"
        }
    }
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let id = peripheral.identifier
        guard sessionGate.accepts(central), started, central.state == .poweredOn, connections[id] == nil,
              !attempted.contains(id), attempted.count < 128, connections.count < 8 else { return }
        attempted.insert(id)
        peers.removeAll { $0.id == id }
        if peers.count >= 128 { peers.removeFirst() }
        peers.append(Peer(id: id, name: "Устройство MeshGram", verificationDetail: "Проверяем подпись узла…"))
        let link = Link(peripheral, owner: self)
        connections[id] = link
        peripheral.delegate = link
        armTimeout(link, deadline: now + 15)
        central.connect(peripheral)
    }
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard sessionGate.accepts(central) else { return }
        guard let link = connections[peripheral.identifier], link.peripheral === peripheral, current(link) else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverServices([CBUUID(string: MeshWire.service)])
    }
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard sessionGate.accepts(central) else { return }
        disconnected(peripheral)
    }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard sessionGate.accepts(central) else { return }
        disconnected(peripheral)
    }
    private func disconnected(_ peripheral: CBPeripheral) {
        guard let link = connections[peripheral.identifier], link.peripheral === peripheral else { return }
        close(link, reason: "Соединение закрыто", cancelConnection: false)
    }
    private func close(_ link: Link, reason: String, cancelConnection: Bool = true) {
        guard connections[link.peripheral.identifier] === link else { return }
        connections.removeValue(forKey: link.peripheral.identifier)
        confirmationGate.invalidate(link.peripheral.identifier)
        assemblers.removeValue(forKey: link.peripheral.identifier)
        link.timeout?.cancel()
        link.timeout = nil
        link.timeoutToken = nil
        let incomplete = link.queue?.state != .completed
        link.queue?.cancel()
        link.peripheral.delegate = nil
        if let index = peers.firstIndex(where: { $0.id == link.peripheral.identifier }) {
            peers[index].verificationDetail = "Соединение закрыто"
            if incomplete { peers[index].sendingDetail = reason }
        }
        if cancelConnection && link.peripheral.state != .disconnected {
            central?.cancelPeripheralConnection(link.peripheral)
        }
    }
    private func armTimeout(_ link: Link, deadline: TimeInterval) {
        link.timeout?.cancel()
        let token = UUID()
        link.timeoutToken = token
        let item = DispatchWorkItem { [weak self, weak link] in
            guard let self, let link, self.current(link), link.timeoutToken == token else { return }
            if var queue = link.queue {
                guard queue.deadline != nil else { return }
                let expired = queue.expire(now: self.now)
                link.queue = queue
                if !expired, let next = queue.deadline { self.armTimeout(link, deadline: next); return }
            }
            self.close(link, reason: "Время ожидания соединения или ответа истекло")
        }
        link.timeout = item
        DispatchQueue.main.asyncAfter(deadline: .now() + max(0, deadline - now), execute: item)
    }
    private func servicesDiscovered(_ link: Link, error: Error?) {
        guard current(link) else { return }
        guard error == nil, let service = link.peripheral.services?.first(where: { $0.uuid == CBUUID(string: MeshWire.service) }) else {
            close(link, reason: "Сервис обмена недоступен"); return
        }
        link.peripheral.discoverCharacteristics([CBUUID(string: MeshWire.notify), CBUUID(string: MeshWire.write)], for: service)
    }
    private func characteristicsDiscovered(_ link: Link, service: CBService, error: Error?) {
        guard current(link), service.uuid == CBUUID(string: MeshWire.service), !link.notificationRequested else { return }
        guard error == nil,
              let notify = service.characteristics?.first(where: { $0.uuid == CBUUID(string: MeshWire.notify) && $0.properties.contains(.notify) }),
              let write = service.characteristics?.first(where: { $0.uuid == CBUUID(string: MeshWire.write) && $0.properties.contains(.write) }) else {
            close(link, reason: "Устройство не поддерживает подтверждаемую запись"); return
        }
        link.notify = notify
        link.write = write
        link.notificationRequested = true
        link.peripheral.setNotifyValue(true, for: notify)
    }
    private func notificationChanged(_ link: Link, characteristic: CBCharacteristic, error: Error?) {
        guard current(link), characteristic === link.notify else { return }
        guard error == nil, characteristic.isNotifying else {
            close(link, reason: "Подписка на обмен прервана"); return
        }
        guard !link.helloStarted, let identity = searchIdentity else { return }
        link.helloStarted = true
        do {
            let packet = try PacketCrypto.makeHello(identity: identity, createdAtMs: Int64(Date().timeIntervalSince1970 * 1000))
            // Keep each frame within the default ATT payload; Android does not assemble prepared long writes.
            var queue = try HelloWriteQueue(payload: JSONEncoder().encode(packet), frameKey: UInt32.random(in: .min ... .max),
                maximumWriteLength: min(20, link.peripheral.maximumWriteValueLength(for: .withResponse)))
            try queue.activate(now: now)
            link.queue = queue
            if let index = peers.firstIndex(where: { $0.id == link.peripheral.identifier }) {
                peers[index].sendingDetail = "Передаём публичное приветствие без имени и аватара…"
            }
            sendNext(link)
        } catch { close(link, reason: "Не удалось подготовить приветствие") }
    }
    private func sendNext(_ link: Link) {
        guard current(link), let characteristic = link.write, var queue = link.queue else { return }
        do {
            let write = try queue.takeNext(now: now)
            link.queue = queue
            guard let write, let deadline = queue.deadline else { return }
            armTimeout(link, deadline: deadline)
            link.peripheral.writeValue(write.data, for: characteristic, type: .withResponse)
        } catch { link.queue = queue; close(link, reason: "Время передачи приветствия истекло") }
    }
    private func writeResponded(_ link: Link, characteristic: CBCharacteristic, error: Error?) {
        guard current(link), characteristic === link.write, var queue = link.queue, let pending = queue.inFlight else { return }
        do {
            let accepted = try queue.acknowledge(sequence: pending.sequence, succeeded: error == nil, now: now)
            link.queue = queue
            guard accepted else { return }
            link.timeout?.cancel()
            link.timeout = nil
            link.timeoutToken = nil
            if queue.state == .completed {
                if let index = peers.firstIndex(where: { $0.id == link.peripheral.identifier }) {
                    peers[index].sendingDetail = "Приветствие записано по блютузу. Приём другим приложением ещё не подтверждён."
                }
            } else { sendNext(link) }
        } catch { link.queue = queue; close(link, reason: "Запись приветствия не подтверждена") }
    }
    private func clearConfirmation(_ link: Link, detail: String) {
        confirmationGate.invalidate(link.peripheral.identifier)
        if let index = peers.firstIndex(where: { $0.id == link.peripheral.identifier }) {
            peers[index].fingerprint = nil
            peers[index].nodeId = nil
            peers[index].verificationDetail = detail
        }
    }
    private func received(_ link: Link, characteristic: CBCharacteristic, error: Error?) {
        guard current(link), characteristic === link.notify else { return }
        guard error == nil, let data = characteristic.value else {
            clearConfirmation(link, detail: "Ошибка приёма. Подтверждение ключей недоступно.")
            return
        }
        do {
            var assembler = assemblers[link.peripheral.identifier] ?? FrameAssembler()
            let payload = try assembler.receive(data)
            assemblers[link.peripheral.identifier] = assembler
            guard let payload else { return }
            clearConfirmation(link, detail: "Проверяем новый пакет…")
            let packet = try JSONDecoder().decode(HelloPacket.self, from: payload)
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            guard packet.originNodeId != searchIdentity?.nodeId, packet.hops == 0,
                  packet.createdAtMs >= now - 900_000, packet.createdAtMs <= now + 120_000,
                    let index = peers.firstIndex(where: { $0.id == link.peripheral.identifier }) else {
                clearConfirmation(link, detail: "Приветствие устарело или не подходит для прямой сверки.")
                return
            }
            try confirmationGate.observe(packet, peerId: link.peripheral.identifier,
                localNodeId: searchIdentity?.nodeId ?? "", nowMs: now)
            peers[index].name = packet.discoverable == true ? packet.alias : "Узел MeshGram"
            peers[index].fingerprint = packet.fingerprint
            peers[index].nodeId = packet.originNodeId
            peers[index].verificationDetail = "Подпись проверена. Личность собеседника ещё не подтверждена."
        } catch {
            // Never log profile content or promote malformed packets to verified peers.
            clearConfirmation(link, detail: "Пакет не проверен. Подтверждение ключей недоступно.")
            status = "Получен неподдерживаемый или непроверенный пакет"
        }
    }
}
