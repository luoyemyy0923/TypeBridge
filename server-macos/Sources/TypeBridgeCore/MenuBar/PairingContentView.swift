import SwiftUI

struct PairingContentView: View {
    @ObservedObject var controller: PairingWindowController

    var body: some View {
        VStack(spacing: 14) {
            VStack(spacing: 4) {
                Text("用安卓 App 扫码配对")
                    .font(.headline)
                Text("一次一码 · 5 分钟内有效 · 新码会让旧码立即失效")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let payload = controller.payload,
               let image = QRCodeGenerator.image(for: payload.uriString, pointSize: 260) {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 260, height: 260)
                    .padding(14)
                    .background(Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            } else if controller.paired {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color.green.opacity(0.12))
                    .frame(width: 288, height: 288)
                    .overlay {
                        VStack(spacing: 10) {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 54))
                                .foregroundStyle(.green)
                            Text("已连接")
                                .font(.title3)
                                .fontWeight(.semibold)
                            Text("如需配对新的设备，请点击下方「重新生成二维码」")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 24)
                        }
                    }
            } else {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color.secondary.opacity(0.12))
                    .frame(width: 288, height: 288)
                    .overlay {
                        Text(controller.message ?? "二维码已过期")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding()
                    }
            }

            if let payload = controller.payload {
                VStack(spacing: 3) {
                    Text("\(payload.ip):\(String(payload.port))")
                        .font(.system(.body, design: .monospaced))
                    Text("指纹 \(payload.fingerprint)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("剩余 \(controller.secondsRemaining) 秒")
                        .font(.caption)
                        .foregroundStyle(controller.secondsRemaining < 60 ? .red : .secondary)
                }
            }

            if controller.payload != nil {
                if controller.interfaces.count > 1 {
                    Picker("网卡", selection: $controller.selectedInterfaceID) {
                        ForEach(controller.interfaces) { interface in
                            Text("\(interface.name) · \(interface.ip)")
                                .tag(Optional(interface.id))
                        }
                    }
                    .pickerStyle(.menu)
                    .onChange(of: controller.selectedInterfaceID) { newValue in
                        controller.selectInterface(id: newValue)
                    }
                } else if let first = controller.interfaces.first {
                    Text("网卡 \(first.name) · \(first.ip)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            HStack {
                Button("重新生成二维码") {
                    controller.regenerate()
                }
                Button("复制连接串") {
                    controller.copyURI()
                }
                .disabled(controller.payload == nil)
            }

            Text(controller.paired
                 ? "二维码已被使用；需要配对新的设备时，点击「重新生成二维码」。"
                 : "若 IP 变化，二维码会自动刷新；旧码同时作废。")
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
        .padding(20)
        .frame(width: 380)
    }
}
