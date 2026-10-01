type Props = {
    coreSink: string;
    moduleSink: string;
    onChange: (field: "coreSink" | "moduleSink", value: string) => void;
};

/** Creation-only destinations; later reference maintenance does not modify these immutable sinks. */
export function RewardSinks({ coreSink, moduleSink, onChange }: Props) {
    return (
        <>
            <label>
                Core reward sink (optional)
                <input
                    value={coreSink}
                    onChange={(event) => onChange("coreSink", event.target.value)}
                    placeholder="Defaults to this fee wallet"
                />
            </label>
            <label>
                Module reward sink (optional)
                <input
                    value={moduleSink}
                    onChange={(event) => onChange("moduleSink", event.target.value)}
                    placeholder="Defaults to this fee wallet"
                />
            </label>
            <p className="field-note">
                Reward sinks are immutable and fixed at creation. A distinct address per account keeps
                receipts attributable; leaving both blank uses this fee wallet for both sinks. Use addresses
                whose keys you control. Changing reference hosting does not redirect these rewards.
            </p>
        </>
    );
}
