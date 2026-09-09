import SwiftUI
import AVFoundation

struct ScannerView: UIViewControllerRepresentable {
    let scanned: (String) -> Void
    func makeUIViewController(context: Context) -> QRScannerController { QRScannerController(scanned: scanned) }
    func updateUIViewController(_ controller: QRScannerController, context: Context) {}
    static func dismantleUIViewController(_ controller: QRScannerController, coordinator: ()) { controller.stop() }
}
final class QRScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let session = AVCaptureSession(), queue = DispatchQueue(label: "com.bloxbean.companion.camera")
    private let scanned: (String) -> Void
    private var preview: AVCaptureVideoPreviewLayer?
    private var delivered = false
    private let retry = UIButton(type: .system)
    init(scanned: @escaping (String) -> Void) { self.scanned = scanned; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("Storyboard initialization is unsupported") }
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = .black
        retry.setTitle("Scan again", for: .normal)
        retry.backgroundColor = UIColor.black.withAlphaComponent(0.75)
        retry.tintColor = .white; retry.layer.cornerRadius = 12
        retry.translatesAutoresizingMaskIntoConstraints = false
        retry.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.delivered = false
            self.queue.async { [session = self.session] in session.startRunning() }
        }, for: .touchUpInside)
        view.addSubview(retry)
        NSLayoutConstraint.activate([retry.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            retry.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -16),
            retry.widthAnchor.constraint(equalToConstant: 130), retry.heightAnchor.constraint(equalToConstant: 42)])
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: configure()
        case .notDetermined: AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in DispatchQueue.main.async { if granted { self?.configure() } else { self?.message("Camera access is off. Enable it in Settings, or paste a request instead.") } } }
        default: message("Camera access is off. Enable it in Settings, or paste a request instead.")
        }
    }
    private func configure() {
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back), let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input) else {
            message("No camera available. Use Paste request or Import file in the simulator."); return
        }
        if session.canSetSessionPreset(.hd1920x1080) { session.sessionPreset = .hd1920x1080 }
        if (try? camera.lockForConfiguration()) != nil {
            if camera.isFocusModeSupported(.continuousAutoFocus) { camera.focusMode = .continuousAutoFocus }
            if camera.isExposureModeSupported(.continuousAutoExposure) { camera.exposureMode = .continuousAutoExposure }
            camera.unlockForConfiguration()
        }
        session.addInput(input); let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { message("QR scanning is unavailable."); return }
        session.addOutput(output); output.setMetadataObjectsDelegate(self, queue: .main); output.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: session); layer.videoGravity = .resizeAspectFill
        view.layer.insertSublayer(layer, at: 0); preview = layer; layer.frame = view.bounds
        queue.async { [session] in session.startRunning() }
    }
    override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); preview?.frame = view.bounds }
    func stop() { queue.async { [session] in session.stopRunning() } }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); stop() }
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !delivered, let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject, let text = object.stringValue else { return }
        delivered = true; stop(); scanned(text)
    }
    private func message(_ text: String) {
        let label = UILabel(); label.text = text; label.numberOfLines = 0; label.textAlignment = .center; label.textColor = .white
        label.font = .systemFont(ofSize: 15); label.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(label)
        NSLayoutConstraint.activate([label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24), label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24), label.centerYAnchor.constraint(equalTo: view.centerYAnchor)])
    }
}
