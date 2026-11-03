import SwiftUI

public struct ActionEditorView: View {
    @ObservedObject private var store = ActionStore.shared

    @State private var editing: ActionItem?
    @State private var revealed: Set<String> = []

    public init() {}

    public var body: some View {
        VStack(spacing: 0) {
            if store.actions.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "text.badge.plus")
                        .font(.largeTitle)
                        .foregroundStyle(.secondary)
                    Text("还没有自定义指令")
                        .font(.headline)
                    Text("新增后，安卓端会收到只读指令库；点击即可在 Mac 上执行。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(store.actions.sorted { $0.order < $1.order }) { action in
                        row(for: action)
                            .padding(.vertical, 4)
                    }
                }
            }

            Divider()

            HStack {
                Button {
                    editing = ActionItem(
                        id: store.newID(),
                        name: "",
                        behavior: .textEnter,
                        text: "",
                        order: store.nextOrder(),
                        sensitive: false
                    )
                } label: {
                    Label("新增指令", systemImage: "plus")
                }

                Spacer()

                Text("共 \(store.actions.count) 条 · v\(store.version)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(12)
        }
        .frame(minWidth: 520, minHeight: 520)
        .sheet(item: $editing) { item in
            ActionEditSheet(item: item) { draft in
                store.upsert(draft)
                editing = nil
            } onCancel: {
                editing = nil
            }
        }
    }

    private func row(for action: ActionItem) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(action.name.isEmpty ? "未命名指令" : action.name)
                        .font(.headline)
                    if action.sensitive {
                        Text("敏感")
                            .font(.caption2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1)
                            .background(Color.orange.opacity(0.18), in: Capsule())
                            .foregroundStyle(.orange)
                    }
                }
                Text(previewText(for: action))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            if action.sensitive, !action.text.isEmpty {
                Button {
                    if revealed.contains(action.id) {
                        revealed.remove(action.id)
                    } else {
                        revealed.insert(action.id)
                    }
                } label: {
                    Image(systemName: revealed.contains(action.id) ? "eye.slash" : "eye")
                }
                .buttonStyle(.borderless)
                .help("显示 / 隐藏固定文本")
            }

            Text("顺序 \(action.order)")
                .font(.caption2)
                .foregroundStyle(.tertiary)

            Button("编辑") { editing = action }
                .buttonStyle(.borderless)

            Button(role: .destructive) {
                store.delete(action.id)
            } label: {
                Image(systemName: "trash")
            }
            .buttonStyle(.borderless)
            .help("删除指令")
        }
    }

    private func previewText(for action: ActionItem) -> String {
        let behavior = action.behavior.displayName
        if action.sensitive {
            let masked = revealed.contains(action.id) ? action.text : "••••••"
            return "\(behavior) · \(masked.isEmpty ? "（空）" : masked)"
        }
        return "\(behavior) · \(action.text.isEmpty ? "（执行时使用安卓端实时文本）" : action.text)"
    }
}

private struct ActionEditSheet: View {
    let onSave: (ActionItem) -> Void
    let onCancel: () -> Void

    @State private var draft: ActionItem

    init(item: ActionItem, onSave: @escaping (ActionItem) -> Void, onCancel: @escaping () -> Void) {
        self.onSave = onSave
        self.onCancel = onCancel
        _draft = State(initialValue: item)
    }

    private var canSave: Bool {
        !draft.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !(draft.sensitive && draft.text.isEmpty)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(draft.name.isEmpty ? "新增指令" : "编辑指令")
                .font(.headline)

            Form {
                TextField("名称", text: $draft.name)

                Picker("行为", selection: $draft.behavior) {
                    ForEach(ActionBehavior.allCases) { behavior in
                        Text(behavior.displayName).tag(behavior)
                    }
                }

                Stepper("顺序 \(draft.order)", value: $draft.order, in: 0 ... 999)

                Toggle("固定文本不下发到安卓（敏感）", isOn: $draft.sensitive)
                    .help("敏感指令的文本只保存在 Mac 端，安卓端执行时只回传 actionId")
            }

            VStack(alignment: .leading, spacing: 6) {
                Text("固定文本")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TextEditor(text: $draft.text)
                    .font(.system(.body, design: .monospaced))
                    .frame(minHeight: 110)
                    .overlay {
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(Color.secondary.opacity(0.25))
                    }
            }

            if draft.sensitive, draft.text.isEmpty {
                Text("敏感指令必须填写固定文本，否则执行时什么都不会发生。")
                    .font(.caption)
                    .foregroundStyle(.red)
            } else if !draft.sensitive, draft.text.isEmpty {
                Text("固定文本为空时，执行会使用安卓端在输入框里填写的实时文本。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            HStack {
                Spacer()
                Button("取消", action: onCancel)
                    .keyboardShortcut(.cancelAction)
                Button("保存") { onSave(draft) }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!canSave)
            }
        }
        .padding(20)
        .frame(width: 440)
    }
}
