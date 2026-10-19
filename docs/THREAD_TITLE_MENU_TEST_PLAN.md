# Thread title checkbox

Source-only implementation; compilation and device tests have not been run.

- Thread overflow now contains the checkable RU/EN item "Thread title".
- It uses the existing show_original_post_title preference and its unchanged default (false).
  This is the same global switch as Interface > Show title in original post, not a per-thread
  state and not a change to Hide thread title in toolbar.
- The current thread rebinds only its loaded original post. No Activity recreation, list reset,
  forced scroll or network refresh is requested. Cached pages compare the preference on resume.
- Windowed threads without a loaded OP persist the choice; subsequent ordinary OP binding
  reads the preference. No window fetch is performed solely for this setting.
- Existing ViewUnit title suppression exceptions remain: dialogs/previews/threadshots and
  toolbar-hidden or differing/translated headings are not mechanically removed.
- No new copy pipeline: when displayed, the subject is part of the original post's selectable
  text. A subjectless thread still has no separate subject; the existing comment remains copyable.

Manual acceptance:

1. Open a thread with a real subject and matching toolbar title. Enable the checkbox: the subject
   appears above the OP. Double-tap the OP, select its subject and copy. Disable: duplicate hides.
2. Reopen overflow, rotate, restart the app and inspect Interface settings: the value agrees.
3. Switch between two cached threads; the second uses the same value. Reply/post selection,
   attachments and scroll position must not reset solely from opening the menu.
4. Check an empty/loading thread, a windowed thread whose OP is outside the loaded window,
   hidden-toolbar mode, translated posts and a thread without a subject. No crash or extra fetch.
