package chat.matron.android.models

/// The For you list's tabs: what still needs the user, the questions,
/// decisions and notices that are closed, and the spare-allowance backlog
/// (open tasks labelled `spare-allowance`, next first). Not persisted: the
/// list opens on [OPEN] every launch, since that is the working list. Ported
/// from matron-apple's `ForYouTab`.
enum class ForYouTab(val title: String) {
    OPEN("Needs you"), CLOSED("Done"), SPARE("Spare allowance")
}
