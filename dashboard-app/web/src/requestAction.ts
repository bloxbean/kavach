/** UI labels for distinct maintenance APIs; bulk republication never invokes reclamation. */
export function requestAction(action: string | null) {
    if (action === "Repair reference") return "repair-reference";
    if (action === "Reclaim reference") return "reclaim-reference";
    return action;
}
