package io.github.devicebridge.core;

/** Information a caller must show before dispatching a device-changing operation. */
public final class OperationApproval {
    public enum Risk { CHANGE, DESTRUCTIVE, SECURITY, DISCONNECT }
    public final String deviceTarget;
    public final String operation;
    public final String target;
    public final String consequence;
    public final Risk risk;

    public OperationApproval(String deviceTarget, String operation, String target,
                             String consequence, Risk risk) {
        this.deviceTarget = required(deviceTarget, "deviceTarget");
        this.operation = required(operation, "operation");
        this.target = required(target, "target");
        this.consequence = required(consequence, "consequence");
        if (risk == null) throw new IllegalArgumentException("risk is required");
        this.risk = risk;
    }

    /** Display-only text; never pass this description to a shell. */
    public String describe() {
        return "Device: " + deviceTarget + "\nOperation: " + operation + "\nTarget: "
                + target + "\nConsequence: " + consequence;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty() || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
