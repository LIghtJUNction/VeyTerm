# Interactive browser preview — local QA

Verified on 2026-10-30 with system Chromium 156.0.8078.73 and the existing Playwright CLI 0.1.22. No browser or npm dependencies were downloaded. Only `docs/preview/**` is part of this change.

Open `veyterm-preview.html` in a browser, or serve this directory with a local static server. The page is one self-contained HTML file. The folder icon opens Files; the same icon, **← Terminal**, or Escape returns to the terminal. Escape first closes an open menu/dialog.

## Data and behavior

Both panes browse the shared, in-memory `/demo` tree. Their locations, back/forward history, filters, sort order and selections are independent. Checkbox selection chooses operation inputs; opening a filename navigates a folder or opens its editor/tool preview. Focus or selection in a pane makes it the operation source; the action strip names the destination.

Reload restores the original samples. File operations never access Android storage or user files. Archive, APK and Network offer switchable illustrative details and explain the corresponding Android actions. They do not claim to extract, inspect an executable package, connect or start a transfer service.

## Browser checks passed

| Area | Actual browser interactions and assertions |
| --- | --- |
| Navigation | Open `src` on the left and `saved` on the right; use independent back/forward, parent and Demo root; deleting a directory being viewed by the other pane safely returns that pane to Demo root. |
| Filter / sort | Name filtering and empty-result hint; name descending with folders first; the other pane's filter/order remains unchanged. |
| Text editor | Open README, edit and save, reopen and verify text; dirty Cancel offers Keep editing / Discard changes; discarded text does not replace the saved version. Escape follows the same confirmation flow. |
| Copy | Cancel without changes; multi-selection and recursive folder copy; default Keep both; Skip; Replace blocked until explicitly confirmed, then verify destination text. Batch-generated names remain unique. |
| Move | Left → right and right → left; verify source removal; conflict Skip leaves source intact; Keep both preserves the existing destination. |
| Safety | Same-directory and descendant destinations are rejected. Replacing an ancestor containing the source is rejected before mutation. Duplicate names and slash-containing names are rejected. |
| Other operations | Create a directory in the active pane; rename; cancel deletion and verify preservation; confirm deletion and verify removal. |
| Tools | Archive Contents / Extract / Create, APK Package / Manifest / DEX–Java, Network Remote / Transfer / Security; open archive/APK sample filenames directly. States explicitly report that no backend job ran. |
| Terminal regression | Output, CTRL/ALT pressed state and typed command draft survive Files round trips; command submission shows the original preview notice; extra keys toggle and both Keyboard entry points still work. |
| Session regression | New session from Files returns to terminal; select, named create/cancel, rename, close/cancel and eight-session limit work; menu arrow navigation and Escape focus restoration work. |
| Layout / access | Actual copy/edit/return at 360 px; create folder at 150% text; edit/move-conflict/return in landscape; create/return in landscape at 150%. Keyboard focus has a visible 2 px outline. Both themes checked. No page errors. |

The original list-overlap defect found during testing was corrected: after copying enough files to extend the right list, lower checkboxes remain clickable above the action strip. The complete operation sequence then passed again.

### Measured viewports

| Viewport | Text size | Pane widths | Result |
| --- | --- | --- | --- |
| 1280 × 800 | 14 px | 640 / 640 px | Side by side; no horizontal overflow |
| 360 × 800 | 14 px | 180 / 180 px | Side by side; no horizontal overflow |
| 360 × 800 | 21 px (150%) | 180 / 180 px | Side by side; no horizontal overflow |
| 800 × 360 | 14 px | 400 / 400 px | Side by side; no horizontal overflow |
| 800 × 360 | 21 px (150%) | 400 / 400 px | Side by side; no horizontal overflow |

Text enlargement was applied to the root font (14 → 21 px). At large text sizes or short heights, the workspace scrolls vertically so controls remain reachable. Landscape uses a compact header and pairs each pane's filter with its sort control. These are local browser checks, not Android build/device acceptance.

## Screenshots

- [Desktop, dark](screenshots/files-desktop-dark.png)
- [360 px portrait, dark](screenshots/files-360-dark.png)
- [360 px portrait, 150% text](screenshots/files-360-font150.png)
- [Landscape, light](screenshots/files-landscape-light.png)

## Quick repeat

1. Reload, open Files, and navigate the panes separately. Filter the left pane, change its sort, then clear the filter.
2. Edit/save/reopen README. Make another edit, cancel, keep editing, then cancel and discard.
3. Select left `notes.txt`, copy to the right, and check Keep both. Repeat with Skip; repeat with Replace and its explicit confirmation. Try a multi-item folder copy and a move in each direction.
4. Create/rename/delete a demo directory. Try canceling the dialogs and using existing or invalid names.
5. At 360 × 800, 800 × 360, and 150% root text, repeat an operation and return to the terminal. Switch light/dark through Terminal actions and use Tab to inspect focus.
6. Open each tool and change its detail view. Return to the terminal; check modifiers, text input, draft retention and session create/rename/close.
