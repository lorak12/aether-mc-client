1. Add normal microsoft login thru email and passowrd and everything else like to a normal app with the ability to login using the link as well.
2. Fix the server entry
3. Look thru every mod and keep the ones from Lunar Client while crosschecking it with that client
4. Add modrinth support as well as curseforge, allow for dowlonading of modpacks and revelat features
5. Add the optimizations to the client
6. Add more cosmetics and add a store/wardrobe for them

Fixes:

1. Fix search in the mod list in game (done 2026-10-08: ranked search, focus model, type-to-search)
2. Remake the in game ui to be more modern and easier to read like Lunar Client (done 2026-10-08, see STATUS.md)

Game ui changes:

1. Main menu

- overhaul of the vanilla main menu of the game with an account swithcer it the top left cornerr.
- top left corner we have the close button as a X icon, and a button to turn on or off the custom main menu and the current coins balance of the custom currency for buying cosmetics
- then we have the logo in the middle with the singleplayer and multiplayer buttons, and a store button with a different color and an add bellow it
- in the bottom right corner we can put the recent annoucements with a nice sliding slides so only one is visible at the time but multiple are auto swiping from left to right and a small semi transparent text saying that we are nto affiliated with mojang or microsoft. Do not distribute!
- in the bottom in the middle we can put the client options that open general settings of the client, wardrobe for cosmetics, chat for social interactions in the client, settings for minecraft settings, relms, and a mod menu form the mod mod menu that is optional if not installed
- in the bottom left corner we can put the client version as a nice semi transparent text like Aether Client 1.21.11 (v0.1.0-2651) or whatever the version and optionaly build is.

2. In game Right Shift Menu

When opened it makes a 1 px border (with a 2px border around that border so its not on the edge of the screen and is visible), in the middle we can have the logo and the name with nice font in high resolution and 3 buttons, emotes (which will come soon), mods for the mod list (client mods), and wardrobe.

Clicking mods will open a nice semi transparent window in the middle with the name of the client and its icon, 3 tabs for mods, settings and waypoints (for the waypoints mod), on the left we can find a list of profiles of mod placements and settings with a customizable name and icon, ability to create a new profile (Save as new profile), and an edit hud layout button, then we have the mod list,
the mod list has categories for ALL, NEW, HUD, SERVER, MECHANIC, some displaying options as a nice list of 3 rows with small 8:1 (wider than tall) ratio buttons with the icon and mod name and settigns for that mod button, there is also a searchbar, the second display mode is large 1:2 (higher than wider) with its icon, name and ENABLE/DISABLE text at the bottom, clicking any of the mods takes you to their settigns page
The settings page is straight forward, it has a back button, name, searchbar, reset settings button, a small description of what it does, and the list of options where we have some input types such as: COLOR_PICKER, TOGGLE, NUMBER_VALUE, TEXT_VALUE, OPTION, SLIDER, KEYBIND, BUTTON (for example to copy something to clipboard)

In @example folder i have put every single file that i want to have as a profile inside the game that can be changed also there is the updated list of mods theoreticaly and their options along side normal options like position, color, backround, outline/border, ect.
