package chat.matron.android.events

/// Namespace for the Matron-specific event/content-key constants. Each constant
/// matches the wire-format string the bridge / bots emit. Use the constants (not
/// string literals) at call sites so a future rename catches every reference.
object MatronEventType {
    /// Tool invocation by an agent — args, status, result.
    const val TOOL_CALL = "chat.matron.tool_call"

    /// Bot asking the user a structured question.
    const val ASK_USER = "chat.matron.ask_user"

    /// Session-level metadata (model, project name, started-at, etc.).
    const val SESSION_META = "chat.matron.session_meta"

    // Matron X buttons protocol — CONTENT KEYS / relation types, not event
    // `type` strings. A buttons prompt is an ordinary message carrying the
    // `chat.matron.buttons` key in its content.

    /// Content key: `{ mode, prompt, buttons: [{id, label, value}] }`.
    const val BUTTONS = "chat.matron.buttons"

    /// Content key on the user's reply: `{ selected_values: [String] }`.
    const val BUTTON_RESPONSE = "chat.matron.button_response"

    /// `rel_type` of the relation on a button response.
    const val BUTTON_ANSWER = "chat.matron.button_answer"
}
