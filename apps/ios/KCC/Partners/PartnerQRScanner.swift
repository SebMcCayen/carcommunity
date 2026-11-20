@preconcurrency import AVFoundation
import SwiftUI
import UIKit

struct PartnerRedemptionScanner: View {
    let companyId: String
    @State private var token: String?
    @State private var inspection: PortalReservation?
    @State private var receipt: PortalReceipt?
    @State private var requestId = UUID().uuidString
    @State private var busy = false
    @State private var errorKey: String?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    private let client = PartnerPortalClient.createIfAvailable()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s3) {
                PortalText("portal.scanInstructions").font(.subheadline)
                if let receipt {
                    Label(portalString("portal.redeemed"), systemImage: "checkmark.circle.fill").font(.title2)
                    Text(receipt.reservation.title).font(.headline)
                    Text(receipt.reservation.companyName)
                    Text("\(portalString("portal.receipt")): \(receipt.receiptId)").font(.footnote).textSelection(.enabled)
                    Button { reset() } label: { PortalText("portal.scanNext") }.kccPrimaryAction()
                } else if let inspection {
                        VStack(alignment: .leading, spacing: KccSpacing.s3) {
                            Text(inspection.title).font(.title2.bold())
                            Text(inspection.companyName).font(.headline)
                            Text(inspection.terms)
                            PortalText("portal.confirmNoIdentity").font(.footnote).foregroundStyle(.secondary)
                            Button { Task { await redeem() } } label: { PortalText("portal.confirmRedemption") }
                                .kccPrimaryAction().disabled(busy || scenePhase != .active)
                            Button { reset() } label: { PortalText("portal.discardScan") }.frame(minHeight: 48).disabled(busy)
                        }
                } else if scenePhase == .active && errorKey == nil && token == nil {
                    PortalCameraScanner(onScan: found, onFailure: { errorKey = "portal.cameraError" })
                        .frame(height: 300)
                        .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
                        .accessibilityLabel(portalString("portal.cameraDescription"))
                }
                if busy { ProgressView() }
                PortalError(key: errorKey)
                if errorKey != nil && inspection == nil {
                    Button { reset() } label: { PortalText("portal.scanAgain") }.frame(minHeight: 48).disabled(busy)
                    if let settings = URL(string: UIApplication.openSettingsURLString) {
                        Link(portalString("portal.cameraSettings"), destination: settings).frame(minHeight: 48)
                    }
                }
                PortalText("portal.onlineRequired").font(.footnote).foregroundStyle(.secondary)
                Spacer(minLength: 0)
                }.padding(KccSpacing.s4).frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle(portalString("portal.scan"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("partners.close") { dismiss() }.disabled(busy) } }
            .interactiveDismissDisabled(busy)
            .onChange(of: scenePhase) { _, phase in
                // A staff token is never drawn. Keep an inspected request in memory
                // so a lost confirmation response can retry the same idempotency key.
                if phase != .active && inspection == nil && !busy { token = nil }
            }
            .onDisappear { token = nil; inspection = nil }
        }.kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
        .accessibilityIdentifier("partnerPortal.scanner")
    }
    private func reset() {
        token = nil; inspection = nil; receipt = nil; errorKey = nil; requestId = UUID().uuidString
    }
    private func found(_ value: String) {
        guard !busy, token == nil, scenePhase == .active, !value.isEmpty, value.utf8.count <= 2_048 else { return }
        token = value; busy = true; errorKey = nil
        Task {
            defer { busy = false }
            guard let client else { errorKey = "portal.unavailable"; return }
            do {
                let result = try await client.call("inspect", ["token": value], as: PortalInspection.self)
                guard !Task.isCancelled, token == value else { return }
                guard scenePhase == .active else { token = nil; return }
                guard result.reservation.companyId == companyId else { errorKey = "portal.wrongCompany"; return }
                inspection = result.reservation
            } catch { errorKey = PartnerPortalCoordinator.message(error) }
        }
    }
    private func redeem() async {
        guard let token, let client, !busy, scenePhase == .active else { return }
        busy = true; errorKey = nil
        defer { busy = false }
        do {
            let result = try await client.call("redeem", ["token": token, "requestId": requestId], as: PortalReceipt.self)
            guard !Task.isCancelled, self.token == token else { return }
            guard result.reservation.companyId == companyId else { errorKey = "portal.wrongCompany"; return }
            receipt = result; self.token = nil; inspection = nil
        } catch { errorKey = PartnerPortalCoordinator.message(error) }
    }
}

private struct PortalCameraScanner: UIViewControllerRepresentable {
    let onScan: @MainActor @Sendable (String) -> Void
    let onFailure: @MainActor @Sendable () -> Void
    func makeUIViewController(context: Context) -> PortalCameraController {
        PortalCameraController(onScan: onScan, onFailure: onFailure)
    }
    func updateUIViewController(_ controller: PortalCameraController, context: Context) {}
    static func dismantleUIViewController(_ controller: PortalCameraController, coordinator: ()) { controller.stop() }
}

/// All capture-session setup/start/stop run serially off the UI thread.
private final class PortalCaptureSession: @unchecked Sendable {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "com.kcc.partner-qr.capture")
    private var configured = false
    private var metadataOutput: AVCaptureMetadataOutput?
    func start(delegate: AVCaptureMetadataOutputObjectsDelegate, onFailure: @escaping @MainActor @Sendable () -> Void) {
        let delegateBox = PortalMetadataDelegateBox(delegate)
        queue.async { [self] in
            if !configured {
                do {
                    guard let camera = AVCaptureDevice.default(for: .video) else { throw KccFunctionsError(code: .unavailable) }
                    let input = try AVCaptureDeviceInput(device: camera)
                    let output = AVCaptureMetadataOutput()
                    session.beginConfiguration()
                    guard session.canAddInput(input), session.canAddOutput(output) else {
                        session.commitConfiguration(); throw KccFunctionsError(code: .unavailable)
                    }
                    session.addInput(input); session.addOutput(output)
                    output.metadataObjectTypes = [.qr]
                    metadataOutput = output
                    session.commitConfiguration(); configured = true
                } catch { Task { @MainActor in onFailure() }; return }
            }
            metadataOutput?.setMetadataObjectsDelegate(delegateBox.value, queue: .main)
            if !session.isRunning { session.startRunning() }
        }
    }
    func stop() {
        queue.async { [self] in
            metadataOutput?.setMetadataObjectsDelegate(nil, queue: nil)
            if session.isRunning { session.stopRunning() }
        }
    }
}
private final class PortalMetadataDelegateBox: @unchecked Sendable {
    let value: AVCaptureMetadataOutputObjectsDelegate
    init(_ value: AVCaptureMetadataOutputObjectsDelegate) { self.value = value }
}

private final class PortalCameraController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let capture = PortalCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var permissionTask: Task<Void, Never>?
    private var visible = false
    private let onScan: @MainActor @Sendable (String) -> Void
    private let onFailure: @MainActor @Sendable () -> Void
    init(onScan: @escaping @MainActor @Sendable (String) -> Void, onFailure: @escaping @MainActor @Sendable () -> Void) {
        self.onScan = onScan; self.onFailure = onFailure
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let layer = AVCaptureVideoPreviewLayer(session: capture.session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer); preview = layer
    }
    override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); preview?.frame = view.bounds }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated); visible = true
        permissionTask = Task { [weak self] in
            let allowed: Bool
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: allowed = true
            case .notDetermined: allowed = await AVCaptureDevice.requestAccess(for: .video)
            default: allowed = false
            }
            guard let self, !Task.isCancelled, visible else { return }
            if allowed { capture.start(delegate: self, onFailure: onFailure) } else { onFailure() }
        }
    }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); stop() }
    func stop() { visible = false; permissionTask?.cancel(); permissionTask = nil; capture.stop() }
    nonisolated func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let value = (metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
        Task { @MainActor [weak self] in guard let self, visible else { return }; onScan(value) }
    }
}
