# Firebase Setup for QuietOS Network Cloud Relay

One-time setup. About 10 minutes in a browser. This is what makes the
forgotten-phone relay work when your two phones are on *different*
networks — Phone B at home writes to Firebase, your Motorola picks it up
anywhere.

**What Firebase sees:** only ciphertext. Every relay envelope is
AES-256-GCM encrypted with your pairing key *before* it leaves the phone,
and HMAC-signed. Google can't read your calls or texts.

## Steps

### 1. Create the Firebase project
1. Go to https://console.firebase.google.com
2. Click **Add project** (or **Create a project**)
3. Name it `quietos-network` (or whatever you like)
4. Google Analytics: **off** (you don't need it)
5. Click **Create project**

### 2. Add the Android app
1. In the project overview, click the **Android** icon (Add an app)
2. Android package name — type exactly:
```
com.deanwaylabs.quietos
```
3. App nickname: `QuietOS Network`
4. SHA-1: skip (not needed for this)
5. Click **Register app**

### 3. Download google-services.json
1. On the next screen, click **Download google-services.json**
2. Get that file to the bench — send it to Roman (or place it yourself):
```
~/workspace/quietos/source/app/google-services.json
```
   It goes in the `app/` folder, next to `build.gradle.kts`.
3. Click **Next**, then **Continue to console** (skip the SDK steps —
   the code already has them)

### 4. Enable Realtime Database
1. Left sidebar → **Build** → **Realtime Database**
2. Click **Create Database**
3. Location: pick the closest (us-central1 is fine)
4. Start in **locked mode** — we'll paste our own rules next
5. Click **Enable**

### 5. Paste the security rules
1. In Realtime Database, click the **Rules** tab
2. Delete what's there, paste the contents of `firebase-rules.json`
   (in this same folder)
3. Click **Publish**

What the rules do:
- Each phone authenticates anonymously (no password, no account)
- A phone can only *read* its own inbox: `quietos-relay/{its-own-device-id}`
- A phone can only *write* to its paired peer's inbox
- Nothing else in the database is readable or writable

### 6. Enable Authentication (anonymous)
1. Left sidebar → **Build** → **Authentication**
2. Click **Get started**
3. **Sign-in method** tab → click **Anonymous** → **Enable** → **Save**

That's it. No credit card, no billing — the free Spark plan covers this
easily (a few hundred small writes a day is nothing).

## After setup

1. Tell Roman "Firebase is ready" — he'll build the APK with your
   `google-services.json` baked in
2. Install on **both** phones
3. Pair them (Network Hub → Pair)
4. On **both** phones: Network Hub → **Start cloud relay**
5. Leave Phone B at home, take the Motorola out — missed calls and
   texts flow through, spam stays home

## If something breaks

- "Firebase not configured" in the Hub log → `google-services.json`
  isn't in `app/` or the package name doesn't match
- "Firebase auth failed" → Anonymous sign-in isn't enabled (step 6)
- "Permission denied" writing → database rules aren't published (step 5)
- Local Wi-Fi still works fine without any of this — the cloud relay
  is purely the different-networks path
