# BarterMarket

BarterMarket is a Paper plugin for player-to-player item barter. This test build targets the published Paper 26.2 API (`26.2.build.135-stable`) and Java 25.

## Build

Run `gradle test` and `gradle jar`. The self-contained plugin jar is `build/libs/BarterMarket-1.0.0.jar`.

## Install

Stop the Paper server, back up the server and existing plugin data, copy the jar into `plugins/`, and start the server. Data is stored under `plugins/BarterMarket/`; SQLite is authoritative. Do not edit `database.db` while the server is running.

## Commands

- `/market` opens the paginated marketplace. Use `Send a gift` to choose a player who has joined before, select multiple item types from your inventory, review, and confirm. Gifted items are removed from the sender only after a durable transaction journal is written, then assigned to the recipient's mailbox. Online recipients are notified immediately; offline recipients are notified on their next join. Grant `bartermarket.gift` to control access.
- `/gift` opens the same gift recipient flow directly.
- `/mail` opens your persistent email inbox. Click a message to read it; unread messages are marked read when opened. Send with `/mail <player> <message>` or `/mail send <player> <message>`. Player-name autocomplete uses usernames saved when players join. Messages are limited to 2,000 characters, stored independently from item deliveries, and notify online recipients immediately or offline recipients on their next join.
- `/market sell` opens the listing editor. The offer accepts one item type only and packs its quantity across up to seven stacks. Click items in your inventory to add them; left-click selects a stack and right-click selects one item. Use `Choose requested item` to browse materials even when you do not own them. Select a requested item in the editor and use the adjacent plus/minus buttons to set its quantity. Click an offer item to remove the entire offer; decrement a request to zero to remove it, then publish.
- `/market listings` shows your active listings. Click a listing to cancel it; reserved items are returned through the mailbox.
- `/market mailbox` opens pending deliveries. Click one entry to claim that entire delivery, or use Claim All. A delivery requires enough free inventory capacity.
- `/market claim` claims all pending deliveries.
- `/market undo` cancels your most recently published eligible active listing.
- `/market help` lists commands.
- `/market admin` currently reports the available recovery policy; inspection, export, backup, restore, and operator-driven recovery are not implemented in this test build.

## Transaction and recovery behavior

Offers are serialized with Paper's ItemStack byte serializer. Listing creation records a reservation journal before removing offered items. Purchases conditionally lock one active listing, validate and debit the buyer's requested items, then atomically persist both mailbox deliveries and mark the listing sold. Cancellation similarly changes the listing state and creates the return delivery in one SQLite transaction. SQLite uses WAL mode, `synchronous=FULL`, foreign keys, and a 5-second busy timeout.

Mailbox claims are all-or-nothing. The delivery is changed from `PENDING` to `CLAIMING` before inventory insertion, then marked `CLAIMED`. If a crash occurs in this gap, startup quarantines the delivery rather than risking a duplicate. Listing reservations and purchases interrupted at uncertain inventory/database boundaries are also quarantined for review; they are not automatically replayed or granted.

SQLite and a Minecraft inventory cannot share an atomic transaction. A crash after the server mutates an inventory but before the corresponding durable state update can therefore leave an item entitlement ambiguous. This build retains the journal and quarantines ambiguous operations; it does not provide automated administrator resolution or guarantee loss-free recovery in that crash window.

## Verification limits

`gradle test` exercises SQLite conditional listing locks, mailbox claim locks, transaction rollback, and quarantine SQL using file-backed databases. It does not launch a Paper server and does not simulate Bukkit inventory events, player disconnects, item metadata round trips, or server crashes. Test with two accounts and a backed-up test server before allowing real players to use it. This is an early test build, not a production-ready marketplace.