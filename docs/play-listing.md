# Horizon Run – Google Play listing

Copy-paste text and the answers for Play Console's setup forms.

## Create app

| Field | Answer |
|---|---|
| App name | **Horizon Run** |
| Default language | English (United States) |
| App or game | **Game** |
| Free or paid | **Free** (the Full Version is an in-app purchase; a free app can't be switched to paid later, and a paid listing would block the free + upgrade model) |
| Declarations | Tick the Developer Program Policies and US export laws boxes |

## Store listing

**App name** (max 30): `Horizon Run`

**Short description** (max 80):

> Tilt to steer, tap to jump! Race across a tiny planet and dodge trees and bears.

**Full description** (max 4000):

> Run toward the horizon of a tiny, curved planet! Trees, rocks, deer, foxes and bears rise up over the edge of the world and rush toward you. How far can you go?
>
> HOW TO PLAY
> • Tilt your phone left and right to steer
> • Tap the left side to jump – tap again in the air for a high jump over bears and deer
> • Hold the right side to sprint
> • Your speed keeps climbing the longer you survive
>
> COLLECT & SCORE
> • Grab trails of glowing flowers – collect all five for a Bouquet bonus
> • Hunt for rare golden flowers worth big points
> • Run through glowing snails to slow back down when things get too fast
> • Jump solid rock walls, duck under nothing (you can't!), and squeeze past low branches
> • Earn bonus points for close calls
>
> HOLIDAY THEMES
> Around Halloween, Christmas, Thanksgiving, Valentine's Day, Easter, St. Patrick's Day, the 4th of July and New Year's, the flowers turn into pumpkins, presents, hearts, eggs and more – with snow, falling leaves or confetti to match.
>
> MORE
> • Light and dark (night) mode
> • A glowing line on the ground shows where your high score is – cross it for a chime
> • Scoreboard of your last 10 runs
> • Built-in tutorial that teaches every move
> • Play offline – no account needed
>
> FULL VERSION (one-time in-app purchase)
> • No ads, ever
> • Free revive every run – no ad to watch
> • Pick any holiday theme, any time of year
> • Zen mode: no crashes, just run and collect
> • Lifetime stats, an all-time top 10 and 12 badges to earn
> • Upgrading keeps all your scores
>
> Horizon Run is free to play with optional ads.

**Graphics**

| Asset | File | Notes |
|---|---|---|
| App icon | `docs/store/icon-512.png` | 512 × 512 PNG |
| Feature graphic | `docs/store/feature-graphic-1024x500.png` | 1024 × 500 |
| Phone screenshots | take on your phone | 2–8 landscape screenshots. Good ones: a run with a flower trail, a high jump over a bear, a holiday theme (use the Scores-title preview), dark mode, the crash screen with a funny line, the scoreboard |

**Category:** Games → **Arcade**  ·  **Tags:** Arcade, Running, Casual, Action, Offline
**Contact email:** your support address  ·  **Privacy policy:** https://adamselite.com/apps/horizon-run/privacy/

## App content (Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | https://adamselite.com/apps/horizon-run/privacy/ |
| Ads | **Yes, my app contains ads** |
| App access | **All functionality is available without special access** |
| Content rating | Category **Game**. Violence: none / cartoon none (you crash into trees, no harm shown). No fear, sex, language, drugs, gambling. Users can't interact or share content. Does not share location. Has in-app purchases: Yes. Expected result: **Everyone / PEGI 3** |
| Target audience | **13–15, 16–17, 18+** (don't select under-13 ages – that would put the app under the Families policy, which needs child-safe ad settings) |
| Appeals to children? | "No" is the honest answer for a 13+ target; Google may still review this |
| News app | No |
| COVID-19 apps | Not a contact tracing / status app |
| Data safety | see below |
| Government app | No |
| Financial features | None |
| Health | None |
| Advertising ID | **Yes** – used for **Advertising or marketing** (the Google Mobile Ads SDK uses it) |

## Data safety form

**Does your app collect or share any of the required user data types?** Yes (through the Google Mobile Ads SDK).
**Is all of the user data collected encrypted in transit?** Yes.
**Do you provide a way for users to request that their data is deleted?** No (the game stores nothing on a server; ad data is handled by Google).

| Data type | Collected | Shared | Purpose | Optional? |
|---|---|---|---|---|
| Location → **Approximate location** (from IP address) | Yes | Yes | Advertising or marketing | Required |
| App activity → **App interactions** | Yes | Yes | Advertising, Analytics | Required |
| App info and performance → **Crash logs**, **Diagnostics** | Yes | Yes | Analytics | Required |
| Device or other IDs → **Device or other IDs** | Yes | Yes | Advertising or marketing, Fraud prevention, security and compliance | Required |

Not collected: name, email, contacts, photos, files, messages, health, financial info (Google Play handles payments), precise location.

## In-app product

Monetize → Products → **In-app products** → Create product

| Field | Value |
|---|---|
| Product ID | `full_version` (must match exactly) |
| Name | Horizon Run Full Version |
| Description | No ads, free revives, any holiday theme, Zen mode, stats and badges. |
| Price | **$1.99** (Play converts other currencies) |
| Status | **Active** |

This tab appears once a build with the billing library has been uploaded (any build from 17 on).

## Before production

1. **Real ad ids:** create the app in AdMob, then replace the test ids in `Ads.java` (rewarded + interstitial) and the `APPLICATION_ID` in `AndroidManifest.xml`. Link AdMob to the Play app.
2. **Testing track:** personal developer accounts created after 13 Nov 2023 must run a **closed test with at least 12 testers for 14 days** before Production unlocks. Older accounts can go straight to Production after internal testing.
3. Upload the latest `HorizonRun.aab` from the GitHub release page.
