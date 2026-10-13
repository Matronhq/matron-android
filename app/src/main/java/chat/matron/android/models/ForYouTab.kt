package chat.matron.android.models

/// The For you list's two tabs: what still needs the user, and the
/// questions, decisions and notices that are closed. Not persisted: the list
/// opens on [OPEN] every launch, since that is the working list. Ported from
/// matron-apple's `ForYouTab`.
enum class ForYouTab(val title: String) {
    OPEN("Needs you"), CLOSED("Done")
}
