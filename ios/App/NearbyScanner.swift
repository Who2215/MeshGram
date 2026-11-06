import CoreBluetooth
import Combine
import Foundation
import MeshGramCore

final class NearbyScanner: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    struct Peer: Identifiable {
        let id: UUID
        var name: String
        var fingerprint: String?
        var detail: String
    }
    @Published private(set) var peers: [Peer] = []
    @Published private(set) var status = "Поиск ещё не запущен"
    private var central: CBCentralManager?
    private var connections: [UUID: CBPeripheral] = [:]
    private var assemblers: [UUID: FrameAssembler] = [:]
    private var nodeId: String?
    private var started = false

    func start() {
        do { nodeId = try IdentityStore.loadOrCreate().nodeId }
        catch { status = "Защищённое хранилище недоступно. Поиск не запущен."; return }
        started = true
        if central == nil { central = CBCentralManager(delegate: self, queue: .main) }
        else if central?.state == .poweredOn { beginScan() }
    }
    func stop() {
        started = false
        central?.stopScan()
        for peripheral in connections.values { central?.cancelPeripheralConnection(peripheral) }
        connections.removeAll()
        assemblers.removeAll()
        status = "Поиск остановлен"
    }
    private func beginScan() {
        central?.scanForPeripherals(withServices: [CBUUID(string: MeshWire.service)], options: nil)
        status = "Ищем MeshGram рядом"
    }
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn: if started { beginScan() }
        case .unauthorized: status = "Разрешите доступ к блютузу в настройках iPhone"
        case .poweredOff: status = "Блютуз выключен"
        default: status = "Блютуз пока недоступен"
        }
    }
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard connections[peripheral.identifier] == nil, connections.count < 8 else { return }
        peers.removeAll { $0.id == peripheral.identifier }
        if peers.count >= 128 { peers.removeFirst() }
        peers.append(Peer(id: peripheral.identifier, name: "Устройство MeshGram", detail: "Проверяем подпись узла…"))
        connections[peripheral.identifier] = peripheral
        peripheral.delegate = self
        central.connect(peripheral)
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self, weak peripheral] in
            guard let self, let peripheral, peripheral.state == .connecting else { return }
            self.central?.cancelPeripheralConnection(peripheral)
        }
    }
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard started else { central.cancelPeripheralConnection(peripheral); return }
        peripheral.discoverServices([CBUUID(string: MeshWire.service)])
    }
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        disconnected(peripheral)
    }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        disconnected(peripheral)
    }
    private func disconnected(_ peripheral: CBPeripheral) {
        connections.removeValue(forKey: peripheral.identifier)
        assemblers.removeValue(forKey: peripheral.identifier)
        if let index = peers.firstIndex(where: { $0.id == peripheral.identifier }) {
            peers[index].detail = "Соединение закрыто"
        }
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil else { central?.cancelPeripheralConnection(peripheral); return }
        for service in peripheral.services ?? [] where service.uuid == CBUUID(string: MeshWire.service) {
            peripheral.discoverCharacteristics([CBUUID(string: MeshWire.notify)], for: service)
        }
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else { central?.cancelPeripheralConnection(peripheral); return }
        for characteristic in service.characteristics ?? [] where characteristic.uuid == CBUUID(string: MeshWire.notify) {
            peripheral.setNotifyValue(true, for: characteristic)
        }
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard started, error == nil, characteristic.uuid == CBUUID(string: MeshWire.notify), let data = characteristic.value else { return }
        do {
            var assembler = assemblers[peripheral.identifier] ?? FrameAssembler()
            let payload = try assembler.receive(data)
            assemblers[peripheral.identifier] = assembler
            guard let payload else { return }
            let packet = try JSONDecoder().decode(HelloPacket.self, from: payload)
            try PacketCrypto.verifyHello(packet)
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            guard packet.originNodeId != nodeId, packet.hops == 0,
                  packet.createdAtMs >= now - 900_000, packet.createdAtMs <= now + 120_000,
                  let index = peers.firstIndex(where: { $0.id == peripheral.identifier }) else { return }
            peers[index].name = packet.discoverable == true ? packet.alias : "Узел MeshGram"
            peers[index].fingerprint = packet.fingerprint
            peers[index].detail = "Подпись проверена. Личность собеседника ещё не подтверждена."
        } catch {
            // Do not log public-profile content or promote malformed/unverified packets to peers.
            status = "Получен неподдерживаемый или непроверенный пакет"
        }
    }
}
