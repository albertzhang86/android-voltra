import Foundation
import CoreBluetooth

// NDJSON transport for local hardware tests. Protocol decisions and trainer commands
// belong to the test client; this process only owns GATT connections while it runs.
final class BluetoothBridge: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private var central: CBCentralManager!
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var writers: [UUID: CBCharacteristic] = [:]
    private let service = CBUUID(string: "E4DADA34-0867-8783-9F70-2CA29216C7E4")
    private let write = CBUUID(string: "A010891D-F50F-44F0-901F-9A2421A9E050")
    private let notify = CBUUID(string: "55CA1E52-7354-25DE-6AFC-B7DF1E8816AC")

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
    }

    func emit(_ event: String, device: UUID? = nil, fields: [String: Any] = [:]) {
        var record = fields
        record["event"] = event
        if let device = device { record["device"] = device.uuidString.lowercased() }
        guard let data = try? JSONSerialization.data(withJSONObject: record, options: [.sortedKeys]),
              let line = String(data: data, encoding: .utf8) else { return }
        print(line)
        fflush(stdout)
    }

    func receive(_ line: String) {
        do {
            guard let data = line.data(using: .utf8),
                  let request = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let operation = request["operation"] as? String,
                  let identifier = request["device"] as? String,
                  let id = UUID(uuidString: identifier) else {
                emit("error", fields: ["message": "Expected operation and CoreBluetooth device UUID"])
                return
            }
            switch operation {
            case "connect":
                guard central.state == .poweredOn, peripherals[id] == nil,
                      let peripheral = central.retrievePeripherals(withIdentifiers: [id]).first else {
                    emit("error", device: id, fields: ["message": "Bluetooth unavailable, unknown device or duplicate connection"])
                    return
                }
                peripherals[id] = peripheral
                peripheral.delegate = self
                central.connect(peripheral)
            case "write":
                guard let peripheral = peripherals[id], peripheral.state == .connected,
                      let writer = writers[id], let hex = request["hex"] as? String,
                      !hex.isEmpty, hex.count % 2 == 0, hex.count <= 2046 else {
                    emit("error", device: id, fields: ["message": "Invalid write or device not ready"])
                    return
                }
                var bytes = [UInt8]()
                var cursor = hex.startIndex
                while cursor < hex.endIndex {
                    let end = hex.index(cursor, offsetBy: 2)
                    guard let byte = UInt8(hex[cursor..<end], radix: 16) else {
                        emit("error", device: id, fields: ["message": "Invalid hexadecimal data"])
                        return
                    }
                    bytes.append(byte)
                    cursor = end
                }
                let type: CBCharacteristicWriteType = writer.properties.contains(.write) ? .withResponse : .withoutResponse
                guard bytes.count <= peripheral.maximumWriteValueLength(for: type) else {
                    emit("error", device: id, fields: ["message": "Frame exceeds negotiated write size"])
                    return
                }
                peripheral.writeValue(Data(bytes), for: writer, type: type)
                if type == .withoutResponse { emit("written", device: id) }
            case "disconnect":
                if let peripheral = peripherals[id] { central.cancelPeripheralConnection(peripheral) }
            default:
                emit("error", device: id, fields: ["message": "Unknown operation"])
            }
        } catch {
            emit("error", fields: ["message": error.localizedDescription])
        }
    }

    func stop() {
        for peripheral in peripherals.values { central.cancelPeripheralConnection(peripheral) }
        // Let cancellation callbacks finish before terminating the transport process.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { exit(0) }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        emit("bluetooth", fields: ["poweredOn": central.state == .poweredOn])
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        peripherals.removeValue(forKey: peripheral.identifier)
        emit("error", device: peripheral.identifier, fields: ["message": error?.localizedDescription ?? "Connection failed"])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        writers.removeValue(forKey: peripheral.identifier)
        peripherals.removeValue(forKey: peripheral.identifier)
        emit("disconnected", device: peripheral.identifier, fields: ["message": error?.localizedDescription ?? "Disconnected"])
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let found = peripheral.services?.first(where: { $0.uuid == service }) else {
            emit("error", device: peripheral.identifier, fields: ["message": error?.localizedDescription ?? "VOLTRA service missing"])
            return
        }
        peripheral.discoverCharacteristics([write, notify], for: found)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil,
              let writer = service.characteristics?.first(where: { $0.uuid == write }),
              let updates = service.characteristics?.first(where: { $0.uuid == notify }) else {
            emit("error", device: peripheral.identifier, fields: ["message": error?.localizedDescription ?? "VOLTRA characteristics missing"])
            return
        }
        writers[peripheral.identifier] = writer
        peripheral.setNotifyValue(true, for: updates)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            emit("error", device: peripheral.identifier, fields: ["message": error.localizedDescription])
        } else if characteristic.isNotifying {
            emit("ready", device: peripheral.identifier)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            emit("error", device: peripheral.identifier, fields: ["message": error.localizedDescription])
        } else { emit("written", device: peripheral.identifier) }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            emit("error", device: peripheral.identifier, fields: ["message": error.localizedDescription])
        } else if let bytes = characteristic.value {
            emit("notification", device: peripheral.identifier,
                 fields: ["hex": bytes.map { String(format: "%02x", $0) }.joined()])
        }
    }
}

let bridge = BluetoothBridge()
DispatchQueue.global().async {
    while let line = readLine() {
        DispatchQueue.main.async { bridge.receive(line) }
    }
    DispatchQueue.main.async { bridge.stop() }
}
RunLoop.main.run()
