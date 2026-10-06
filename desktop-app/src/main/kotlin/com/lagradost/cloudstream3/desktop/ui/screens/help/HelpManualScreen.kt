package com.lagradost.cloudstream3.desktop.ui.screens.help

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class HelpArticle(
    val section: String,
    val title: String,
    val summary: String,
    val steps: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val recovery: String? = null,
)

private val helpArticles = listOf(
    HelpArticle("Start here", "What Auras Orbit does", "Auras Orbit is a desktop client. You choose and install extensions that provide catalogs and playback sources.", notes = listOf("Adding a repository makes its extension catalog available; it does not install every extension.", "Catalogs, playback sources, subtitles, metadata and availability depend on your chosen providers.", "Profiles, bookmarks, history and playback progress are stored locally. Playback synchronization through an external watch-tracking account is not supported.")),
    HelpArticle("Start here", "Install and launch", "Install the Windows app and complete the first launch.", steps = listOf("Download a published Windows build from the project release page. The source-code repository is not a ready-to-run app package.", "For the portable ZIP, extract it to a folder you control and keep the application, app, and runtime folders together.", "Start Auras-Orbit.exe.", "Choose an existing local profile or create one. You can open Help before choosing a profile from the profile screen."), notes = listOf("Windows 10 or 11, 64-bit is required. The embedded player uses the Microsoft Edge WebView2 Evergreen Runtime.", "Standard installations store app data under %APPDATA%\\AurasOrbit. Portable copies store it in AurasOrbitData beside the application."), recovery = "If the app reports that WebView2 is missing, install the Evergreen Runtime from Microsoft, then choose Check again after installing. Restart the app if the player still cannot open."),
    HelpArticle("Start here", "Watch your first title", "Create a profile, install a provider, find a title and start playback.", steps = listOf("Select or create a local profile.", "Open Extensions, choose Repositories, enter a repository address or short code, and activate Add.", "Open Browse Plugins, choose a provider from that repository and install it. Installing a repository alone does not install the providers inside it.", "Open Search or Home and find a title. Search results depend on installed providers.", "Open the title, choose an episode if it is a series, then activate Play.", "When playback starts, use the player controls or documented keyboard shortcuts. Close the player to return to the app."), recovery = "If the catalog is empty, verify that an extension is installed and enabled, then refresh with F5. For playback failures, open the source or server selection and retry another available source."),
    HelpArticle("Profiles and data", "Create, switch and edit profiles", "Each local profile can keep its own viewing history and saved library.", steps = listOf("Open the profile manager from the profile control in the top bar, or choose Add Profile on the profile screen.", "Enter a name and optionally choose an avatar, color, Kids Profile option or PIN.", "Activate Save Profile. To switch profiles, open the profile manager and select a profile."), notes = listOf("Deleting a profile permanently removes its associated watch history, bookmarks and custom lists.", "The Kids Profile option currently changes content highlighting; do not rely on it to block content."), recovery = "If a PIN is forgotten, use another available profile and the profile manager. The app does not provide account-based PIN recovery."),
    HelpArticle("Profiles and data", "Understand local data and privacy", "Know where Auras Orbit keeps profile information and what to exclude from support reports.", notes = listOf("Profiles, settings, extension files and data, credentials, history and bookmarks are stored in local app data.", "Standard installations use %APPDATA%\\AurasOrbit. Portable copies use AurasOrbitData beside the app.", "Do not attach profile databases, credentials, tokens or private app-data folders to bug reports.")),
    HelpArticle("Navigation", "Move around the app", "Use the dock, screen controls and standard keyboard navigation to reach app areas.", steps = listOf("Use Tab and Shift+Tab to move between available controls; use Enter or Space to activate a focused control.", "The navigation dock opens Home, Explore, Search, Library, Downloads, Watch History, Extensions, Settings and Help & Manual. Its position and appearance can be customized in Settings.", "Use the screen's named Back control to leave a nested page. Escape closes supported overlays and exits fullscreen; its effect depends on the active screen.", "Help & Manual is also available before profile selection. Search this manual by task, screen name or a related word."), notes = listOf("Some poster cards and custom controls need additional keyboard and screen-reader improvements. If an action cannot be reached by keyboard, use the corresponding screen's named action button where available and see Accessibility and known limitations.")),
    HelpArticle("Navigation", "Screen and control index", "Use this index to understand what each major app screen contains and where its main actions are found.", notes = listOf("Profile selection: choose a local profile, open Help & Manual, add a profile, or enter profile management. Locked profiles request a PIN.", "Home: discovery shelves, continue watching, recent activity, and a provider control in the top bar. Use that provider control to select providers, enable catalogs, or manage the Home feed.", "Search: query field, provider selection, suggestions, search history and provider result cards.", "Explore: Catalogs, Torrent Search, Genres and Providers. Category screens show results for a selected provider catalog.", "Title details: Play or Resume, seasons and episodes, library status, custom list assignment, download, trailers and available cast, studio, recommendation and collection sections.", "Person, Studio and Full Cast: metadata details, credits and links to related titles when that data is available.", "Library: watch-status filters, My Lists, search, sort, provider filtering, bookmark cards and title actions.", "Watch History: recent playback entries, resume actions and Clear All with confirmation.", "Downloads: search, active and completed download entries, play, pause/resume/cancel actions, delete, storage folder and maintenance.", "Extensions: Browse Plugins, Installed, Repositories, Stremio Addons and Update History tabs; repository, language and catalog filters; install, sync, local load and uninstall actions.", "Settings: searchable groups and nested pages. See Settings section index for the full group list.", "Player: playback, source/server, episode, audio, subtitle and settings panels; available controls depend on the stream. F1 opens its player shortcut panel.")),
    HelpArticle("Navigation", "Use Help and search this manual", "Find a task by its name, screen, or a common alternative term.", steps = listOf("Open Help & Manual from the dock or the Help button on the profile screen.", "Type a word such as provider, bookmark, subtitle, sound, watchlist, download or PIN in Search this manual.", "Select a matching article to read its steps and recovery advice.", "Use Back to topics to return to the results, or Back to Home to leave the Help screen."), notes = listOf("The manual is included in the app and does not require a network connection.")),
    HelpArticle("Extensions and sources", "Add a repository and install an extension", "Repositories list extension packages; install providers individually.", steps = listOf("Open Extensions and select Repositories.", "Enter the repository URL or short code and activate Add.", "Open Browse Plugins, select the saved repository, and review the available extensions.", "Choose a provider and activate Install. Wait for the result before navigating away.", "Return to Home, Explore or Search and choose that provider when browsing."), recovery = "If adding or installing fails, check the URL, network connection and update history. Try Sync All after the connection returns. A repository being saved does not mean its extensions are installed."),
    HelpArticle("Extensions and sources", "Extensions screen tabs and filters", "Use Browse Plugins, Installed, Repositories, Stremio Addons and Update History to manage providers and community addons.", notes = listOf("Browse Plugins lets you search the extension catalog and filter by repository, language and supported media category.", "Installed lists loaded providers and offers local installation and uninstall actions.", "Repositories lists saved catalog sources and lets you add or remove a repository.", "Stremio Addons manages community manifests. Update History lists recent extension update events and errors. Sync All refreshes saved repository catalogs.", "The tab names and specific filters can vary by app version.")),
    HelpArticle("Extensions and sources", "Install an external addon", "Add a Stremio-compatible addon from its manifest address.", steps = listOf("Open Extensions and choose Stremio Addons. You can also open Settings, choose Extensions & Sources, then Stremio Addons.", "Enter the addon's manifest URL and activate Install.", "Select an installed addon to review its details.", "In the addon's details, choose Remove and confirm."), notes = listOf("Only install addon addresses that you trust.")),
    HelpArticle("Find and browse", "Search for a title", "Search installed providers and open a matching result.", steps = listOf("Open Search from the dock.", "Enter a title in the search field. Use provider selection to include or exclude providers when that control is available.", "Review the results, including the provider name, and open a result to view its details.", "If suggestions appear, select one or continue entering your own search."), recovery = "If no result appears, check the query, provider selection and installed extensions. Try Explore or an individual provider catalog. Providers can return different titles for the same query."),
    HelpArticle("Find and browse", "Explore catalogs, genres and providers", "Browse discovery shelves and provider catalogs without entering a specific title.", steps = listOf("Open Explore & Catalogs from the dock.", "Choose Catalogs to browse shelves supplied by enabled providers and addons.", "Choose Genres to browse by genre or topic. Genre results use TMDB and require the StreamPlay provider to be loaded and active.", "Choose Providers to browse the available providers, then select a provider catalog or category.", "Open any title result to see its details."), recovery = "If genre browsing says StreamPlay is unsupported or unavailable, install or enable StreamPlay and make it the active provider. Catalog shelves and categories depend on the selected provider or addon."),
    HelpArticle("Find and browse", "Search and play from Torrent Search", "Find magnet results for a title and play one through Orbit's P2P engine.", steps = listOf("Open Explore & Catalogs from the dock and choose Torrent Search.", "Enter a movie or show title and activate Search.", "Review each result's name, size, seed count and peer count, then choose Play.", "If playback is blocked, open Settings > Network & DNS and enable P2P Torrent Streaming. Orbit downloads TorrServer the first time torrent playback is used."), notes = listOf("Torrent Search sends the title query to the Magnetz search service. Torrent playback connects to a peer-to-peer swarm, where other peers and your internet provider can see your public IP address. Only play content you are authorized to access."), recovery = "If no results appear, retry with a shorter title. If a magnet will not play, check that P2P Torrent Streaming is enabled, then retry a result with available seeders."),
    HelpArticle("Find and browse", "Use Home shelves and filters", "Browse discovery, recent activity and continue-watching items on Home.", steps = listOf("Open Home from the dock.", "Move through the available shelves and title cards. Select a title to open its details.", "Select the provider control in the top bar to choose providers and manage which catalogs appear on Home.", "Use Manage Home Screen Feed to choose supported providers, media categories and catalogs."), notes = listOf("Home content depends on enabled providers and profile activity. Refresh feeds with F5.")),
    HelpArticle("Find and browse", "Home screen controls", "Home can show provider catalogs, recent discovery, Library items, recent history and continue-watching entries.", notes = listOf("The first-run Home state links to Browse Plugins when no provider is loaded.", "Select the provider control in the top bar to open Manage Home Screen Feed, where you can select providers, filter by media category, enable or disable catalogs, and reorder providers.", "Title cards open their details page. Continue-watching entries resume saved progress when their provider is available.", "Shelf availability and ordering depend on enabled extensions and the active profile.")),
    HelpArticle("Find and browse", "Search screen controls", "Search offers provider selection, query suggestions, recent searches and result cards.", notes = listOf("Enter a search phrase in the search field. Suggestions may appear while typing; select one to use it.", "Use provider selection to narrow which installed providers receive the search when available.", "Search history and results may vary with the active profile and provider response.", "A result opens its title details. Provider names help distinguish titles that share a name.")),
    HelpArticle("Find and browse", "Explore, category and provider screens", "Explore organizes provider catalogs, genres and topics into browseable result lists.", notes = listOf("Explore's Catalogs destination shows shelves supplied by enabled providers and addons.", "Genres opens the TMDB-backed Genre Browser. It requires the StreamPlay provider to be loaded and active; other providers are not supported there yet.", "Providers opens a provider list. Select a provider catalog or category to open its results.", "Category result screens show titles from that provider and category. Filters and available actions depend on the provider.", "Use the visible Back control to leave a genre, provider or category page and return to its parent.")),
    HelpArticle("Title details", "Read title details and play an episode", "Review provider information, save a title or select a season and episode.", steps = listOf("Open a title from Search, Home, Explore, a category or your library.", "Review the title name, provider, description and any available metadata.", "For a series, choose a season and episode in the episode section.", "Activate Play or Resume. Use the title's library control to save it or change its watch status.", "Explore available cast, crew, trailers, recommendations, collections and screenshots by their section headings."), recovery = "Some metadata, artwork, cast details or trailers may be unavailable if the provider does not supply them or a metadata service cannot be reached. Retry or return to the provider's title listing."),
    HelpArticle("Title details", "People, studios and cast pages", "Follow cast and crew links to related people, studios and full-cast information.", notes = listOf("On a title page, activate an available cast, director, writer, producer or studio name to open its related page.", "Person pages can show credits and related titles; studio pages can show titles associated with that company.", "Use Full Cast to review the available cast and crew list, and choose a related title to open its details.", "These pages depend on provider data and optional metadata integrations. A missing link or empty result means no matching information was supplied.")),
    HelpArticle("Title details", "Save a title to your Library", "Keep a bookmark and set its watch status.", steps = listOf("Open the title's details.", "Activate Add to Library and choose the offered watch status, such as Plan to Watch, Watching or Completed.", "Open Library from the dock to find saved titles. Use its status filters, My Lists, search, sort or provider filter to narrow the list.", "To change or remove a saved title, open it or use its available title actions and confirm the change."), notes = listOf("Library bookmarks are stored locally for the active profile. Clearing watch history is a separate action.")),
    HelpArticle("Playback", "Use player controls", "Control playback with the named controls or verified shortcuts.", steps = listOf("Activate Play from a title or episode. Wait while the app discovers and opens a source.", "Use Play or Pause, seek, volume, mute, fullscreen, playback speed, audio and subtitle controls as available.", "Open episode selection to choose another episode. Open source or server selection to retry a different stream.", "Use the player close or exit control to return to the title page.", "F1 in the player opens its built-in keyboard shortcut reference."), notes = listOf("Available controls vary with the source, media type and player mode. Focus and screen-reader support in the embedded player still need verification."), recovery = "If playback fails, retry the source, choose another server or source, verify WebView2, then return to the title and try another provider."),
    HelpArticle("Playback", "Change audio and subtitle tracks", "Select available tracks and adjust subtitle presentation.", steps = listOf("Open the player audio or subtitle panel.", "Choose a listed audio track or subtitle track. Track names and languages depend on the source.", "Use subtitle search or upload a local subtitle file if the panel offers those actions.", "Use subtitle timing and style controls to adjust synchronization and appearance. Use the reset action to restore subtitle styling defaults."), recovery = "A missing language usually means the stream has no such track. Try another source. Subtitle searches require a working network connection and matching results."),
    HelpArticle("Playback", "Player keyboard shortcuts", "Use shortcuts only when the player has received keyboard input. These are the current production player mappings inspected in source.", notes = listOf("Space or K: play or pause. Left or Right Arrow: seek 10 seconds; Shift+Arrow: seek 2 seconds. Up or Down Arrow: adjust volume by 5 percent.", "M: mute or unmute. F: fullscreen. A: audio mode. P: picture-in-picture. N: next episode.", "0 through 9: seek to the matching ten-percent point. Page Up and Page Down: previous or next chapter when chapters exist.", "Plus/Equals or ]: increase speed. Minus or [: decrease speed. Backspace: restore normal speed.", "C: cycle subtitle track. V: show or hide subtitles. Z and X: adjust subtitle delay. Shift+S or Ctrl+S: screenshot. S may trigger a visible skip action.", "Shift+D or Shift+I: toggle playback statistics. H, F1 or ?: open the player's shortcut panel. Escape closes player panels.", "Keyboard focus behavior currently needs improvement. Single-key playback commands may conflict with screen-reader commands or focused controls; use the labeled controls when available."), recovery = "The Settings shortcut reference lists the same player keys. If a shortcut does not respond, return keyboard focus to the player or use its labeled controls; report any mapping that still behaves differently."),
    HelpArticle("Library and history", "Find or remove a saved title", "Search, sort and filter bookmarks in your local library.", steps = listOf("Open Library from the dock.", "Choose a media-type tab, then use search, sorting or provider filters to narrow saved titles.", "Open a bookmark to view title details, or use its available actions to update or remove it.", "If a provider is missing, install a compatible provider and use the title's recovery action if available."), notes = listOf("Some library card actions currently depend on pointer input and need keyboard support.")),
    HelpArticle("Library and history", "Create and use custom lists", "Group saved titles into profile-specific collections without changing their watch status.", steps = listOf("Open Library and choose My Lists.", "Choose Create list and enter a name.", "To add a title, open its details and choose Add to custom lists, or right-click its Library card and choose Add to Custom Lists.", "Select one or more lists. A title can stay in any watch status and belong to multiple lists.", "Choose My Lists to view a collection. Use Manage lists to rename or delete one.", "In Manage lists, choose Pin to Home to show a non-empty list as a shelf on Home. Choose Unpin to remove it."), notes = listOf("Deleting a list removes only its entries. Its titles remain saved in the Library.", "Custom lists are local to the active profile and are included in library export files.")),
    HelpArticle("Library and history", "Resume or clear watch history", "Continue a title from its saved playback position or remove history entries.", steps = listOf("Open Watch History from the dock or a Continue Watching shelf.", "Choose a history entry to resume. Playback position and history belong to the active local profile.", "To clear all history, activate Clear All and review the confirmation carefully."), notes = listOf("Clearing watch history removes resume information for those entries. It is different from removing Library bookmarks.")),
    HelpArticle("Downloads and files", "Download an episode", "Save supported media for local playback when the provider exposes downloads.", steps = listOf("Open a title and choose an episode.", "Activate its download action and choose an offered quality if prompted.", "Open Downloads to review queued, active and completed items.", "Use Pause All, Resume All or Cancel All when available. Review a confirmation before cancelling or deleting many items."), recovery = "A provider may not expose a downloadable source. If a transfer stalls, check the network and available storage, then retry from the download list."),
    HelpArticle("Downloads and files", "Play, delete or locate downloaded media", "Manage downloaded episodes and local files.", steps = listOf("Open Downloads and search or filter the list.", "Activate Play on a completed item to play it locally.", "Use Open Downloads Folder to locate saved files in Windows Explorer.", "Use Delete to remove an item, or the show's bulk action to remove its episodes. Read and confirm the deletion dialog."), notes = listOf("Deleting a download removes the local media file. It does not necessarily remove the title's library bookmark or history.")),
    HelpArticle("Downloads and files", "Play a local file or stream URL", "Open a local video, direct network stream or magnet link from the app.", steps = listOf("Press Ctrl+O to choose a local video file, or Ctrl+U to open the stream dialog.", "Enter a supported direct stream URL or magnet link and submit it.", "Use the player controls to start, pause or exit playback. For a magnet link, enable P2P Torrent Streaming under Settings > Network & DNS if prompted."), notes = listOf("The file picker and stream dialog may offer formats that depend on the installed MPV playback components. Magnet playback uses P2P and exposes your public IP address to peers in the swarm.")),
    HelpArticle("Settings", "Find a setting", "Search for settings and understand which group owns them.", steps = listOf("Open Settings from the dock.", "Type a setting name or keyword in Settings search.", "Choose the matching setting or group and read its current value before changing it.", "Use the page's Back control to leave a nested settings page. Changes generally apply to this local app or profile according to the setting.")),
    HelpArticle("Settings", "Settings section index", "Locate the main settings groups and advanced pages.", notes = listOf("Appearance & Theme: theme, color, wallpaper, posters, home feed, details layout and navigation dock.", "Playback & Media: video renderer, hardware engine, autoplay, skip automation and player behavior.", "Stream Priorities: source ordering and preferred audio or subtitle languages.", "Audio & Equalizer: audio processing and output options.", "Subtitles & Styling: subtitle appearance and defaults.", "Downloads Engine: download behavior, folder and storage options.", "Profiles & Accounts: local profiles and Discord Rich Presence.", "Metadata & Providers: metadata integrations, TMDB and anime integrations.", "Extensions & Sources: provider extensions, repositories and options. The Stremio Addons tab manages compatible community manifests.", "Network & DNS: connection, name-resolution and P2P torrent-streaming options.", "Developer & Logs: diagnostics and development controls.", "Advanced & Storage: folders, cache, databases, preference reset and factory reset.", "About & Updates: version information and update actions.")),
    HelpArticle("Troubleshooting", "The app is empty or a title is missing", "Check providers, filters and catalog availability.", steps = listOf("Confirm that a compatible provider is installed and enabled in Extensions.", "Refresh Home or the catalog with F5.", "Check Search provider selection, language and catalog filters.", "Try the provider's own catalog or another provider.", "Check Update History for extension errors."), recovery = "Catalog data depends on the provider and may change. A repository alone is not a content provider; install an extension from its catalog."),
    HelpArticle("Troubleshooting", "Playback will not start", "Work through the player setup and source checks.", steps = listOf("Confirm WebView2 Evergreen Runtime is installed.", "For a magnet or torrent source, enable P2P Torrent Streaming under Settings > Network & DNS.", "Retry the current source or use source/server selection to choose another.", "Return to title details and try a different episode or provider.", "Restart the app if the embedded player remains unavailable."), recovery = "If the error continues, note the title, provider, player message and app version. Remove private profile data and credentials from any diagnostic report."),
    HelpArticle("Troubleshooting", "A download failed or storage is low", "Check connection, destination and free disk space.", steps = listOf("Open Downloads and read the item's current status and message.", "Check that Windows can access the download folder and that the drive has free space.", "Resume or retry the item. If needed, change the download location in Settings.", "Use storage maintenance only after reading which files it will remove.")),
    HelpArticle("Accessibility", "Keyboard and screen-reader use", "Auras Orbit is built with Compose for Desktop and uses an embedded web player.", steps = listOf("Use Tab and Shift+Tab to move through focusable controls; press Enter or Space to activate focused buttons.", "Use arrow keys for standard sliders and selection controls when the focused control supports them.", "Use named controls instead of single-letter player shortcuts where possible.", "Use Help & Manual for task steps and keyboard mappings. If focus disappears, press Tab once and listen for the next announced control; Escape closes supported overlays."), notes = listOf("Screen-reader names, selected states, focus order, dynamic announcements and WebView2 compatibility require testing with NVDA and Narrator. This manual does not claim full accessibility conformance."), recovery = "If an essential action has no accessible name or keyboard path, use the manual's nearest alternate workflow and report the screen, control, keyboard or screen reader, and the step where you became stuck."),
    HelpArticle("Accessibility", "Known accessibility work", "The following app improvements are required for independent screen-reader and keyboard use.", notes = listOf("Embedded player: preserve focus instead of blurring a focused control during shortcut handling; give icon-only buttons names and expose checked, selected, expanded and slider-value state.", "Navigation: announce the current destination and retain names for collapsed controls.", "Library cards: support keyboard activation, with a named More actions control and a keyboard path to context actions.", "Dialogs: verify title announcement, initial focus, keyboard containment, Escape behavior and focus return to the invoking control.", "Status: announce search completion, extension installation results, playback errors, track changes and download completion without moving focus.", "Customization: provide keyboard and numeric alternatives for drag-based actions such as avatar cropping.", "These are implementation and runtime validation items. They are not solved by reading this manual.")),
    HelpArticle("Troubleshooting", "Report a problem safely", "Collect enough detail to reproduce a fault without exposing local profile information.", steps = listOf("Record the app version from About or the app window.", "Write the screen, steps, expected result and actual result.", "Include the extension name and any error text. For accessibility issues, name the screen reader and the control that lost focus or lacked a name.", "Remove local profile folders, database files, credentials, tokens and private watch history from attachments.")),
)

object HelpManualState {
    var isOpen by mutableStateOf(false)
}

@Composable
fun HelpManualScreen(
    onBack: () -> Unit,
    onClose: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    var selectedArticle by remember { mutableStateOf<HelpArticle?>(null) }
    val headingFocusRequester = remember { FocusRequester() }
    LaunchedEffect(selectedArticle) { headingFocusRequester.requestFocus() }
    val normalizedQuery = query.trim().lowercase()
    val matchingArticles = remember(normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            helpArticles
        } else {
            helpArticles.filter { article ->
                listOf(article.section, article.title, article.summary)
                    .plus(article.steps)
                    .plus(article.notes)
                    .plus(article.recovery.orEmpty())
                    .any { it.lowercase().contains(normalizedQuery) }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selectedArticle != null) {
                IconButton(onClick = { selectedArticle = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to topics")
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (selectedArticle == null) "Help & Manual" else selectedArticle!!.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.focusRequester(headingFocusRequester).focusable().semantics { heading() },
                )
                Text("Auras Orbit desktop guide · included with the app", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClose != null) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close Help") }
            } else {
                TextButton(onClick = onBack) { Text("Back to Home") }
            }
        }
        Spacer(Modifier.height(14.dp))

        if (selectedArticle == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search this manual") },
                placeholder = { Text("Try provider, watchlist, sound, subtitles, or download") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) ({ TextButton(onClick = { query = "" }) { Text("Clear search") } }) else null,
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            Text("Choose a task. Instructions use the control names shown in the app.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${matchingArticles.size} topics",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                val grouped = matchingArticles.groupBy { it.section }
                grouped.forEach { (section, articles) ->
                    item(key = "heading_$section") {
                        Text(section, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp).semantics { heading() })
                    }
                    items(articles, key = { it.title }) { article ->
                        Card(
                            onClick = { selectedArticle = article },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(article.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(3.dp))
                                Text(article.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (matchingArticles.isEmpty()) {
                    item { Text("No matching topics. Try a screen name or a shorter search.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        } else {
            val article = selectedArticle!!
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
                Text(article.section.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.1.sp, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text(article.summary, style = MaterialTheme.typography.bodyLarge)
                if (article.steps.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text("Steps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    article.steps.forEachIndexed { index, step ->
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (article.notes.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text("Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    article.notes.forEach { note ->
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Text("•", color = MaterialTheme.colorScheme.primary)
                            Text(note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
                article.recovery?.let { recovery ->
                    Spacer(Modifier.height(18.dp))
                    Text("If it does not work", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(6.dp))
                    Text(recovery, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = { selectedArticle = null }) { Text("Back to topics") }
            }
        }
    }
}
