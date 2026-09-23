# Redesign implementation — 22 September 2026

The HTML proposal at `../latentjam-redesign.html` is the source for measurements, hierarchy and screen structure. The first pass deviated from it; this follow-up corrects those deviations and covers the remaining proposed screens.

## Implemented

- Single Settings / sliding page titles / Search header. Measured label positions interpolate with the pager without recomposing browsing lists; first/last pages are centered regardless of title width.
- Artwork-derived background clouds shared across browsing, search, collections and settings. Accents, selected tracks, recommendations, statistics and map selection use the current song color; the existing explicit neutral and SMART color preferences remain supported.
- One outlined split sort control and shell-owned floating Shuffle all action across Tracks, Albums, Artists, Genres and Folders. The button stays in place during page swipes and hides during selection, overlays and alphabet scrubbing.
- Floating 72dp mini-player with 24dp corners, 48dp artwork, compact metadata, transport controls and bottom progress; height/insets adapt for larger text.
- Reference spacing, readable secondary type, scrolling alphabet headings, rounded playing rows and trailing duration/actions. Album cards include artist and an unambiguous year.
- Automatic playlists use a horizontal shelf of 150dp artwork cards with titles and track counts, following the user's preferred structure. Import is in the section header and New playlist is the first list row.
- Collection details have a scrolling 176dp hero, title/metadata, and separate Shuffle / Play / SMART actions. Track actions and information sheets follow the proposal's compact headers, grouped actions and aligned metadata.
- For You uses 132dp shelf covers and a tinted hero. Search uses a 48dp integrated field. Statistics uses an unboxed headline, separate metric tiles and grouped cards. Map uses compact filter pills, a rounded plot and region actions.
- Settings is grouped into cards. Pages has compact draggable rows, switches and accessible move actions. Duplicate management has a summary and grouped copy choices.
- Queue has one menu per row, separate duration, clear artwork and the current-song highlight. Reordering remains available through long press, menu actions and accessibility. Playback mode names and explanations remain available in the chooser.
- Widgets use simple rounded surfaces and cached song-colored clouds with no animation ticker. New labels are included in all existing resource locales.
- Widget follow-up: Portal's artwork scrim darkens toward bottom controls; Pulse adapts its artwork/previous visibility to available width and text scale, preserving 48dp play/next targets and usable metadata space down to its 220dp resizing floor. Deck limits artwork growth by both height and available text width. Compact widgets prioritize titles and transport as system text grows, with optional metadata restored when enough space is available. The three widget styles retain their dark palette on the launcher.

## Screenshot follow-ups

- Statistics: the unboxed listening total inherited black through a transparent settings scaffold. The settings, collection and search screen boundaries now explicitly supply the theme's `onSurface` text color; the statistics total also names it directly. This supports dark and light appearance without altering song-derived backgrounds.
- Phone speaker: replaced the cabinet-style icon with the standard speaker/sound-wave icon in the shared player status and Android output chooser; headphone/Bluetooth indicators retain their meanings.
- Automatic playlists: replaced the wrapping capsules with keyed, lazily composed artwork cards. Kept the existing section headings, custom playlist rows, controls and song-colored background. Cards reuse bounded artwork loading and remembered artwork lookup.
- Selection: transparent compact count header, a rounded floating action bar with readable Share, and text-scaled height/insets. A cached fade behind the mini-player covers row fragments near the home indicator, blocks taps on obscured rows, and leaves enough scroll clearance above the fade. Selection actions draw above the fade.
- Sharing and menus: single-track Share appears near the top of the action sheet; action icons use one outlined style. Playlist overflow keeps its anchored interaction with softer corners, wrapping labels and a divider before Delete. iOS sharing anchors its popover to the current scene for iPad support.
- Map: clearer dots and selected region, inset plot edges, concise counts, no zero-unplayed suffix, and labeled region actions that wrap. Sparse maps get larger dots; dense-map sizing, projection and hit-test caching remain intact.
- Light mode: distinct moving pastel clouds follow the song hue over a nearly neutral base, with darker secondary text to retain contrast. The previous strong flat wash hid the motion and was removed. Light cloud centers use 98% opacity with 15% larger radii; the existing motion freezes on pause and respects Reduce Motion. Browse has a static song-colored header wash, softer artwork shadows, lighter sheets, and explicit neutral/SMART theme roles. The dock fade uses the current theme surface. Dark-mode cloud and artwork shadow settings remain unchanged.
- Artist and genre rows: weighted metadata leaves room for long names, with a subtle trailing disclosure chevron outside selection mode. A hidden alphabet rail reserves no width.
- Playlist menus use a music-list glyph for SMART grouping rather than a link glyph. User-created playlists support Change cover in their menu and from the detail-page artwork, plus Use automatic cover. Selected images are copied into app-owned storage at a bounded size; replacing/resetting removes unused prior images after the playlist write succeeds. Cover references survive restarts and ordinary playlist edits. Metadata-only backups/M3U do not package images; backup merge preserves an existing local cover, while replacement/import uses automatic artwork.
- Keep together in SMART is available from both the playlist row and detail-page menus, using the same durable setting and checkmark. For You cards resolve current custom covers without rebuilding recommendation calculations. Android image imports survive rotation; interrupted process recreation clears a pending import. Cancellation during a cover write cannot leave storage and the in-memory playlist inconsistent.
- Typography: everyday secondary text, including track counts, uses 14sp with a 20sp line height. Small labels use 12sp; conflicting 11sp overrides were removed. Both follow system text scaling. Next Up grows vertically for enlarged text, and statistics ranks reserve scalable space without wrapping digits.
- Widget previews and empty states use a small muted mark over a neutral cloud surface. Portal reserves the bottom area for metadata and controls, so its placeholder no longer stretches behind them; real album covers still fill the artwork area. Empty widgets avoid generating an unused cover-sized bitmap.
- Widget type is larger too: Pulse/Deck artists use 14sp, Deck time and next-up use 12sp, and Portal uses a 16sp title with a 13sp artist. All transport touch targets remain 48dp.
- Portal's artwork and dark scrim now share the root's single rounded outline. The independently rounded scrim exposed bright artwork crescents at the bottom corners; removing its extra corner mask and ending the fade at the opaque widget surface removes that seam without new drawing layers or bitmap work. A fresh Portal layout identity makes existing launcher widgets inflate the corrected layers instead of retaining the old drawable during RemoteViews reapply.

## Interaction follow-ups

- Navigation now shares a 260ms custom easing curve: tab taps, header swipes and pager settling use the same timing while direct dragging stays under the finger. New tab requests cancel earlier requests, including a quick return to the origin before the first transition settles.
- A current-tab tap recenters an interrupted, partially offset page. Decorative mini-player crossfades leave the stable surrounding buttons responsive while excluding outgoing metadata from accessibility.
- Settings gear turns under the press and continues into the destination's back arrow without delaying navigation. The library shifts slightly away as Settings enters from the start side; collection entry uses the opposite direction. Rotation and shifts read animation state only in drawing layers, with one tap haptic and no repeating animation.
- Settings title and content share one navigation clock. Parent settings pages retain scroll positions, stale outgoing callbacks are ignored, and Search/collection scroll positions survive opening and closing Now Playing.
- Full-page transitions use short directional travel and fades without scaling whole lists or animating their layout size. Reduced motion removes spatial movement and gear rotation. Search's focused field remains anchored during keyboard changes.
- Widget Previous/Next controls have no resting circles or borders. They retain their 48dp touch targets and native press ripple; only Play/Pause stays filled.
- Settings enters and leaves from its button's start edge, mirrored for RTL. Nested settings retain normal forward/back navigation.
- The floating Shuffle button fades/scales in and out without shifting when selection controls appear. Hidden/exiting controls cannot receive taps.
- Alphabet feedback stays in one small bubble: immediate finger tracking, a gentle entry/release, and short directional letter transitions. The list jump is not delayed. Cancelled scrubs cancel instead of committing.
- Carousel labels fade at the side edges. Scrolled lists fade into their real backdrop over a 10dp top strip; only that strip is buffered, and lists at the start render directly. Rails and stationary headings remain crisp.
- Removed duplicate built-in long-press pulses and noisy feedback from cancelled or unchanged actions. Queue/playlist/page drag feedback confirms actual changes; queue hold-to-drag remains supported.
- Mini-player swipes follow the finger with bounded layer motion, one threshold cue and live queue-edge checks. Cancelled/no-op gestures remain silent. Compact/large-text layouts preserve metadata space, with Previous retained as a swipe/accessibility action and in the expanded player.
- Reduced motion uses short fades or immediate settling, including scroll-to-top, search, pressed cards and collapse. Equalizer faders now roll back interrupted drags and commit only genuine releases.

## Performance

- Browse gradients are cached drawing brushes, with no blur effect or continuous frame loop.
- Playback position is still observed only in progress/seek components. Background color changes follow the existing one-shot track accent transition.
- Lazy list/grid keys, remembered sorting/metadata, bounded artwork decoding and off-main-thread search/recommendation work remain intact. Only the current row receives changing playback/accent properties.
- New interaction animation state stays in small control/drawing layers. Track title color fades no longer recompose the artwork/gesture row; highlighted text is remembered. No new continuous timer, blur, or per-pointer-event coroutine was added.
- Alphabet scrubbing uses a transparent mirror while hiding the real viewport, preserving the background without duplicate rows. The existing bounded artwork readiness gate controls the final handoff. No preview fade/ticker is needed.
- Widget clouds are generated on the existing renderer and cached (at most eight small bitmaps). Existing event-driven updates and chronometers remain in place.
- Widget layout decisions refresh on system font-size/display-density/orientation changes and application startup. Launcher option bounds are paired by the active orientation, so portrait details are not hidden using landscape height. No polling is added; with no installed widgets, the coordinator exits before scheduling rendering or decoding artwork.
- Release packaging retains R8 and resource shrinking. These are implementation checks, not a physical-device battery or frame-time benchmark.

## Validation status

- Android debug and release packaging, Android lint, and shared iOS simulator compilation pass. Latest test run: 461 Compose host tests and 181 core/library tests passed, zero failures/errors/skips, including page reordering, cover serialization, cancelled writes, bounded decoding, and backup merge/replace behavior.
- Lint reports zero errors and four existing SDK/manifest warnings.
- Resource XML and whitespace checks pass.
- The desktop preview recovered. Runtime inspection on the fictional LJ demo emulator verified the automatic-playlist artwork shelf, playlist popup, light Map, readable library and player, and the dock covering row fragments near the home indicator. The final light player shows separate colored clouds against a light base. Native cover picking, automatic-cover reset and custom-cover replacement were exercised successfully; the chosen cover is reflected in both the row and detail header. The custom cover survived an app update and relaunch; the temporary test cover was then reset to the prior automatic artwork setting. Persistence after reopening storage is also covered by tests.
- The final Android debug build is installed on the demo emulator. All three live home-screen widgets were visually checked at their current portrait sizes: artist labels are visible, Deck elapsed/next-up details fit, and Portal text has the darker lower scrim behind it. Library/playlist secondary text was checked in light and dark appearance.
- Navigation follow-up: native emulator recording confirms Settings entry/back, gear rotation/back-arrow handoff, and tab settling. Opening and closing Now Playing from a scrolled automatic playlist retains the list position. Home-screen inspection confirms bare Previous/Next controls on Pulse and Portal. Android Studio's temporary recording folder was restored after capture. These checks validate appearance and behavior, not physical-device frame-time performance.
- Page reordering uses measured row centers, handles variable text heights, rejects stale ordering, and preserves visibility changes.
- The user supplied an interim-build screenshot showing the opaque scrub preview and disappearing menus. The preview is now transparent with only one viewport drawn, and decorative menu icons remain visible.
- Physical-device haptic feel, sustained frame timing/battery use and the iPad share popover still need hardware validation; compilation and emulator inspection do not establish those results. Widget XML, resizing geometry and rendering caches were reviewed; all launcher sizes have not been visually exercised.

Changes are local and uncommitted. No release has been published and no personal phone has been modified.


## Follow-up: SMART interaction and playlist artwork (2026-09-23)

- Restored the player mode button’s original tap-to-cycle contract (in order → shuffle → SMART); hold still opens direct choices and queue length. Uses the existing controller cycle operation and localized accessibility hint.
- Restyled the hold menu as a rounded, scrollable chooser with full-width radio rows, explicit selected checks, a uniform 10/20/40 queue-length control, and an optional “How SMART works” disclosure. Existing theme colors and translations are reused. Touch targets are at least 48 dp; labels wrap rather than truncate.
- Connected user-playlist and automatic-playlist artwork to the collection hero using route-qualified shared-element keys. The root slot stays stable, the cover travels in the overlay on open and return, and the underlying list fades away. Reduced motion skips the spatial handoff. Rail preview copies never register a duplicate hero.
- Validation: optimized Android release and iOS simulator Kotlin compilation passed. The existing playlist identity, player feel and haptic suites passed (19 tests). Demo emulator: user and automatic playlist open/return, tap cycling while paused, hold menu (opened by the user), length selection 40→20→40, explanation expansion, and SMART selection verified. First-pass artwork motion inspected in a frame sequence; final fade refinement also smoke-tested. No new fatal errors or ANRs seen in the checked Android log.
- Samsung was disconnected during this follow-up; only the emulator has this update. Light-mode and large-font behavior use the shared palette/wrapping/scrolling implementation but were not visually retested in this pass.
- Final arm64 release SHA-256: `82f3d2d96fe17a0026eccba86dd4946d3cf9e4eada115984e611c5ad0bf1fa1d`.

## Follow-up: library random start and playlist modes (2026-09-23)

- Restored the floating library button to a random starting track without changing playback
  mode. SMART therefore starts a fresh recommendation queue from that seed; the shared button
  on the other library tabs has the same behavior.
- Separated collection Play from individual track taps. The hero and overflow Play actions
  explicitly select sequential mode and start from the first track. Shuffle explicitly selects
  shuffle mode and a random start. Individual track taps retain the current mode, including
  when tapping the first track. The shared collection screen gives user/automatic playlists
  and other collections consistent explicit action semantics. Empty collections cannot change
  mode through these actions.
- Validation: Android release assembly and iOS simulator Kotlin compilation passed, along with
  28 existing AppSettings, PlayerFeel and PlaylistPresentationIdentity tests. Runtime verification
  in the updated demo emulator confirmed: Tracks random start retains SMART; playlist Play from
  SMART starts Blue Hour followed by City in Rain in sequential mode; playlist Shuffle enables
  shuffle; tapping the first playlist row in SMART retains SMART; overflow Play also selects
  sequential mode. Left playback paused and restored SMART after checking.
- Build/test log: `/tmp/latentjam-playback-actions-validation.log`. No new background task,
  queue-building path or animation was introduced; the existing platform controllers are reused.

## Follow-up: settings and search motion (2026-09-23)

- Settings now reveals from the measured gear bounds. Its rotating gear becomes the back
  arrow while the title and content follow on the same reversible transition. Measured source
  and destination centers account for landscape cutouts; text and list layout never scale.
- Search expands its rounded field background from the right-hand search button. The search
  glyph travels into the back affordance, and results appear a little later. The actual editor
  remains fixed, receives focus after opening, and releases focus immediately when closing.
- Entry takes 320 ms and return 260 ms with mirrored easing. AnimatedVisibility owns the
  custom progress, retaining outgoing content through the close. Reduced motion uses an
  80 ms fade without spatial travel. RTL geometry is mirrored.
- Progress is read in drawing layers; no per-row animation, new timer, blur, or animated
  layout measurement was introduced. Settings clipping and library dimming end at rest.
- Validation: 63 focused navigation, header, settings, and search tests passed (zero failures
  or errors), including five new geometry/easing/reduced-motion checks. Final Android release
  assembly and iOS simulator Kotlin compilation passed. Android runtime checks covered
  settings/search open and return in portrait and landscape, light/dark appearance, and
  typing a query on the floating on-screen keyboard. Final measured-icon/opacity polish was
  rebuilt, installed, and smoke-tested in both orientations. Checked error logs were empty.
- Intermediate settings/search frames inspected from the native emulator recording
  `/private/tmp/Screen_recording_20260923_133809.webm`. Recording preferences were restored
  to the original Desktop folder; the app was left in portrait with Follow system appearance.
  Docked-keyboard resizing, physical-device haptics, and frame-time performance were not
  measured in this pass. Samsung was not connected; the update is installed in the emulator.
- Logs: `/tmp/latentjam-navigation-validation.log` and
  `/tmp/latentjam-navigation-polish-build.log`. Final arm64 release SHA-256:
  `d7afa9ab08f1a90bf3d92a9fa6eb9042892cdc606563cd2a695c4f63ec8f85fc`.

## Follow-up: collection indexes and editor forms (2026-09-23)

- Albums, artists and genres now show alphabet sections and the rail only for collections
  with at least 12 entries, multiple initials, and roughly two viewports of content. The
  decision uses the compact layout, scaled typography and available window height, so the
  new headers/gutter cannot themselves cause repeated enable/disable relayout. Small
  collections keep their full-width layout. Group headers share their first row's index;
  album headers use the existing full-span grid anchors.
- Removed the root pager's blanket lock on the Map page. Inline map gestures already reserve
  two fingers for map transforms, allowing one-finger horizontal navigation and vertical page
  scrolling. Fullscreen map interaction is unchanged.
- Create/rename playlist and track tag editing share filled, rounded fields and equal-sized
  wrapping actions. Playlist naming requests focus and supports keyboard submission; blank
  names cannot be submitted. Tag actions remain below the scrolling form, Save is disabled
  until something changes, and Cancel discards the draft. Busy fields and actions are guarded.
- Editor routes and playlist seed track IDs now survive recreation without storing whole
  catalogs in saved state. The forms save their drafts; a restored track/rename editor resolves
  its identity after library/playlist hydration.
- Validation before the route-restoration follow-up: 32 collection-index, album-rail,
  alphabet-rail and map-interaction tests passed. Android release assembly and iOS simulator
  compilation passed for collection changes and again for editor forms.
- Emulator checks: compact four-item albums/artists/genres; 26 temporary extra entries for
  long lists, exact rail jumps in all three sections, artwork on arrival, and condensed rail
  in light landscape. Verified Map swipes to both neighbors over the actual plot and vertical
  scrolling. Removed the 26 temporary media records/files and confirmed the original 16
  tracks remain. Dark portrait playlist naming and light portrait tag editing were inspected.
- Logs: `/tmp/latentjam-collection-index-validation.log`,
  `/tmp/latentjam-editors-validation.log`, `/tmp/latentjam-editors-rotation-build.log`.
- A stationary rail that transitions between adjacent indexed tabs is a separate proposed
  interaction, not implemented by this follow-up. Existing rails still belong to their pages.
- Final runtime follow-up: track editor retained an altered title (unsaved) across portrait/
  landscape recreation, Save enabled only after the edit, Cancel restored the original value,
  and reopening edit mode left Save disabled again. In landscape, all fields can scroll while
  the action row stays fixed. New-playlist dialog retained its typed name through rotation;
  inspected light portrait/landscape and dark portrait. Both test drafts were cancelled.
  Docked-IME resizing, an actual tag write, and physical-device testing are not covered by
  these form checks. Samsung data was not used for test fixtures.
- Playlist opening currently starts at the hero (confirmed in `CollectionDetailScreen`).
  Proposed follow-up: finish the cover transition before revealing an offscreen current track,
  cancel that reveal on user interaction, and preserve position on return from Now Playing.
  This behavior is discussed, not changed by the form/index work.
- Final Android release/iOS simulator compilation passed; diagnostic logging was removed.
  Installed the final APK in `emulator-5554`, restored portrait/Follow system appearance,
  and verified launch. The emulator still contains exactly the original 16 media records,
  including the unchanged Blue Hour title; AndroidRuntime error log was empty. Final log:
  `/tmp/latentjam-editors-final-build.log`. APK SHA-256:
  `187e0e7f279f20e88668eaca652cc1e41869d4f13d67558c4275a476bf76f55f`.

## Follow-up: reveal the current track after playlist entry (2026-09-23)

- User and automatic playlists still enter at the hero. A one-time current-track reveal waits
  for both the collection transition and shared-element scope to finish, rather than using a
  timeout that might race the cover on a different device.
- A fully visible row does not scroll. Visibility excludes the floating player's bottom
  inset; a clipped/covered row is revealed with preceding context. The target respects manual
  order and accounts for the hero and any section headings. Other collection kinds retain
  their existing opening behavior.
- A non-consuming pointer observer cancels the pending/active reveal on touch or wheel input.
  Leaving the surface also cancels it. Completion is saveable in the existing browse shell;
  returning from Now Playing, subsequent tracks, or library reconciliation cannot start it
  again. Existing/restored scroll positions take precedence.
- Reduced motion uses a direct list positioning operation. Normal motion reuses LazyList's
  cancellable scroll and distant-item handling; no timer, extra collection copy or perpetual
  playback observer was added. The input observer is removed once it is no longer needed.
- Validation: all 18 PlaylistEntryReveal, CollectionRail and CollectionHideUndo tests passed
  (8 new target/viewport tests). Android release assembly and iOS simulator Kotlin compilation
  passed. On the emulator, opening Recently added with First Light already fully visible
  retained the hero and list position. The user then confirmed the behavior works and requested
  the Samsung install. Touch cancellation/return behavior was code-reviewed; no independent
  frame-time measurement or complete automated gesture test was performed.
- Build/test log: `/tmp/latentjam-playlist-entry-validation.log`. APK SHA-256:
  `b4e8072da052265ee92e5e53bcec5d77ad9a1d1ec6868a2558671a7326c2134c`.

## Follow-up: player handoff, stationary alphabet rail and playlist shortcuts (2026-09-23)

- Installed the preceding playlist-entry build on the user's Samsung as requested. Subsequent
  frame inspection found the playlist hero promoted into the full player's shared-element
  overlay. Playlist source/destination covers now share a nested browse-only scope; the
  mini-player and full player retain their separate outer scope. The playlist entry reveal
  waits for the nested cover transition, and returning from the player preserves scroll.
- Closing the player also exposed an existing bitmap-size jump: the mini-player artwork's
  child immediately measured at 48dp inside larger animated shared bounds. RetainedArtwork's
  crossfade and image now fill those bounds throughout collapse.
- Indexed root pages publish their rail model and vertical viewport to one pager-level host.
  The pill stays at the trailing edge while pages move. Common letters animate to their new
  positions; added/removed letters fade. Tracks' toolbar inset transitions smoothly. Standalone
  collection/detail rails keep their existing behavior. Paging disables rail input until the
  handoff settles, and disposal releases the original page's scrubbing callback.
- Rail rendering pre-composites its existing tint over the theme background so adjacent
  artwork cannot bleed through during paging. No per-frame horizontal viewport registrations,
  extra offscreen pages or new artwork loads are introduced. Condensed letter rendering is
  bounded to 33 labels; reduced motion skips position animation.
- The featured playlist carousel contains exactly Favorites, Recently added, Most played,
  Recently played, in that order, without an Automatic heading. Empty Favorites remains
  discoverable. Move up/down were removed from the visible user-playlist menu; long-press
  dragging remains, with equivalent custom accessibility actions retained on the row.
- Validation: 44 Android host tests passed across AlphabetRail, AlbumRail, CollectionRail,
  CollectionIndex, PlaylistEntryReveal and RailLetterMotion (five new position/bounds tests).
  Final Android release assembly and iOS simulator Kotlin compilation passed. Logs:
  `/tmp/latentjam-player-rail-validation.log`, `/tmp/latentjam-final-motion-build.log`.
- Emulator checks covered Tracks/Albums/Artists/Genres navigation, rail scrubbing, different
  letter sets, compact landscape rails, four carousel entries, playlist options, current-track
  reveal and preserved scroll on return from the player. Recordings were inspected frame by
  frame; final opening/closing and rail morph evidence is in
  `/private/tmp/Screen_recording_20260923_153949.webm` and the contact sheets
  `/private/tmp/player-open-final.jpg`, `/private/tmp/player-close-final.jpg`,
  `/private/tmp/rail-morph-final.jpg`. The final opaque rail tint was applied after recording.
  This is visual validation from a 24fps recording, not a device frame-time benchmark.
- Removed all 26 owned temporary media files/records; MediaStore and the UI again show the
  original 16 tracks. Restored portrait and Android Studio's original Desktop recording
  destination. Installed the final APK in both emulator-5554 and Samsung SM-S928B via update
  without clearing data; emulator launch and the final rail appearance were checked. These
  new transitions were visually tested on the emulator, not independently on Samsung or iOS.
  Final APK SHA-256: `e9a71cebeb36e1311c007451a77ed3c12753150b4b13431aae84af2cade1daed`.

## Follow-up: queue correctness and interaction audit (2026-09-23)

- A fresh playlist Shuffle session now starts its actual playback traversal at the selected
  random seed. Subsequent Next keeps that order and advances its cursor. Playlist Play restores
  the playlist's natural order. Stored playlist rows are not reordered by playback.
- Android queue edits use Media3's serialized player-command path: a private playlist-metadata
  envelope is consumed directly in a service-side ForwardingPlayer override. Custom session
  commands can overtake asynchronous media-item resolution; metadata-change listeners also
  miss repeated commands because MediaMetadata equality ignores Bundle contents. Intercepting
  the ordered command itself avoids both issues without publishing its payload as metadata.
- Play next inserts directly after the current occurrence; Add to queue appends to the visible
  traversal end, including with native shuffle enabled. Moving shuffled rows changes that same
  native traversal while retaining the loaded track and position. Restored ON sessions install
  the saved traversal exactly instead of generating a new permutation. Commands carry only
  indices/one item, with no full-playlist IPC or per-tick shuffle work.
- A SwipeToDismiss confirmation could run twice and delete the next row after the first removal.
  Each keyed row now submits removal once. Queue reordering is enabled in Shuffle too. iOS
  library filtering retains the exact current duplicate occurrence, or the next surviving row
  when the current track was removed. Starting a queue manually from empty clears its stale
  playlist-source label.
- Resume persistence ignores empty startup emissions, but clears the saved session after an
  established queue becomes empty. Paused/seek positions are saved precisely; active playback
  retains ten-second position buckets and queue-ID snapshot caching to bound disk/CPU work.

### Runtime checks on the Android release demo

- Fresh shuffle, repeated fresh shuffle, Next and reordered Next matched the displayed rows.
- Play next and append in Shuffle preserved the relative order of all existing rows.
- Removed current, future, last and only rows. One swipe removed exactly one occurrence;
  removing one of two identical tracks retained the other occurrence and its paused position.
- Empty-queue manual insertion stayed paused. Its source label no longer named the old playlist.
- Repeat OFF stopped at the boundary; ALL wrapped forwards and backwards; manual Next under
  ONE still advanced when a next row existed. Repeat was returned to OFF after verification.
- SMART generated its continuation while retaining the seed and pause state. Returning to OFF
  retained the current recommendation and returned to the original manual source queue.
- Restart/update retained saved shuffle traversal and current occurrence in the middle of it.
- SMART policy changes still intentionally rebuild the unplayed future; this audit does not
  introduce per-occurrence manual-pinning persistence across a recommendation-policy rebuild.
- Final resume checks: paused seek to 1:32 survived process replacement without rounding;
  removing the only row and restarting left the queue empty and the mini-player absent.

### Validation and delivery

- All 622 host tests passed: 107 in core playback and 515 in composeApp, with no failures,
  errors or skipped tests. Regression coverage includes seed/traversal permutations, duplicate
  occurrences, reordering, library retention, startup versus queue-clear persistence, exact
  paused seeks and bounded active-playback writes.
- Final Android release assembly and composeApp iOS-simulator Kotlin compilation passed.
  Log: `/tmp/latentjam-queue-resume-validation.log`; earlier runtime-fix build logs:
  `/tmp/latentjam-queue-audit-ordered-edits.log`, `/tmp/latentjam-queue-swipe-guard.log`.
- Updated emulator-5554 without clearing app data. Samsung was not connected during this audit;
  no new physical-device or iOS runtime verification is claimed. The original demo library and
  saved playlists remain intact; queue-only edits were used for the manual cases above.
- Final arm64 release APK SHA-256:
  `f508d8196ef70fd5738d9f63871581628d33ae97d02e1212892d8760cc0653e1`.
