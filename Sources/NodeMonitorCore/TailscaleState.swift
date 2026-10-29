public enum TailscaleState {
    case connected, needsLogin, needsApproval, stopped, starting, unknown

    public static func fromBackendState(_ state: String) -> TailscaleState {
        switch state {
        case "Running": return .connected
        case "NeedsLogin": return .needsLogin
        case "NeedsMachineAuth": return .needsApproval
        case "Stopped": return .stopped
        case "Starting": return .starting
        default: return .unknown
        }
    }
}
