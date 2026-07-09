# Quick Start - Firebase Backend Setup

## ✅ What You Have

You have a Firebase project already created:
- **Project ID**: `safe-travel-monitor`
- **Project Number**: `897530494352`
- **Package Name**: `com.knightmeya.safetravelmonitor`
- **API Key**: `YOUR_API_KEY_HERE`

---

## 🚀 Setup Steps (5 minutes)

### Step 1: Get Firebase Service Account Key

1. Go to [Firebase Console](https://console.firebase.google.com)
2. Select project `safe-travel-monitor`
3. Click ⚙️ **Project Settings**
4. Go to **Service Accounts** tab
5. Click **Generate New Private Key**
6. Save as `backend/firebase-key.json`
7. **IMPORTANT**: Add to `.gitignore`

### Step 2: Install Firebase Admin SDK

```bash
cd backend
npm install firebase-admin
```

### Step 3: Setup Environment Variables

```bash
cp .env.firebase .env
```

Then edit `.env` and add your Firebase details from the service account JSON:

```env
FIREBASE_PROJECT_ID=safe-travel-monitor
FIREBASE_PRIVATE_KEY_ID=xxx
FIREBASE_PRIVATE_KEY=xxx
FIREBASE_CLIENT_EMAIL=xxx@appspot.gserviceaccount.com
FIREBASE_CLIENT_ID=xxx
FIREBASE_DATABASE_URL=https://safe-travel-monitor.firebaseio.com
```

### Step 4: Update Backend Server

Update `backend/server.js` to use Firebase models:

```javascript
// Replace these lines:
const authRoutes = require('./routes/auth');
const journeyRoutes = require('./routes/journeys');
const locationRoutes = require('./routes/locations');
const notificationRoutes = require('./routes/notifications');

// Already in file, no changes needed
```

The models are already in:
- `backend/models/firebase/User.js`
- `backend/models/firebase/Journey.js`
- `backend/models/firebase/Location.js`
- `backend/models/firebase/Notification.js`

### Step 5: Test Backend

```bash
npm run dev
```

You should see:
```
Server running on port 5000
```

### Step 6: Test API

```bash
curl -X POST http://localhost:5000/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test User",
    "email": "test@example.com",
    "password": "password123",
    "phone": "+2348012345678"
  }'
```

You should get back a token and user info.

---

## 📱 For Your Android App

Your `google-services.json` is already set up!

1. Place it in: `app/google-services.json`
2. In `app/build.gradle`:

```gradle
if (file('google-services.json').exists()) {
    apply plugin: 'com.google.gms.google-services'
}
```

3. Add Firebase dependencies:

```gradle
dependencies {
    // Firebase
    implementation platform('com.google.firebase:firebase-bom:32.2.3')
    implementation 'com.google.firebase:firebase-database-ktx'
    implementation 'com.google.firebase:firebase-auth-ktx'
    implementation 'com.google.firebase:firebase-messaging-ktx'
    
    // For your backend API calls
    implementation 'com.squareup.retrofit2:retrofit:2.9.0'
    implementation 'com.squareup.retrofit2:converter-gson:2.9.0'
}
```

---

## 🌐 Deploy Backend

### Option A: Render.com (Recommended - Free & Easy)

1. Push code to GitHub
2. Go to [render.com](https://render.com)
3. Sign up with GitHub
4. Click "New +" → "Web Service"
5. Select your repo
6. Fill in:
   - **Name**: `safe-travel-backend`
   - **Build Command**: `npm install`
   - **Start Command**: `node server.js`
   - **Plan**: Free
7. Click **Create Web Service**
8. Add environment variables under "Environment"
9. Deploy!

Your backend URL will be: `https://safe-travel-backend.onrender.com`

### Option B: Google Cloud Run (Free tier included)

```bash
# Install Google Cloud CLI
curl https://sdk.cloud.google.com | bash

# Initialize and deploy
gcloud run deploy safe-travel-backend \
  --source . \
  --platform managed \
  --region africa-south1 \
  --allow-unauthenticated
```

### Option C: Firebase Hosting (Free)

```bash
npm install -g firebase-tools
firebase login
firebase init hosting
firebase deploy
```

---

## 🔗 Update Kotlin App URLs

In `app/src/main/kotlin/com/safetravelmonitor/app/api/ApiClient.kt`:

```kotlin
private const val BASE_URL = "https://safe-travel-backend.onrender.com/api/"
```

---

## ✅ Checklist

- [ ] Firebase service account key downloaded
- [ ] `firebase-key.json` saved in backend folder
- [ ] `.env` file created with Firebase credentials
- [ ] `npm run dev` works locally
- [ ] API test returns token
- [ ] Backend deployed to Render.com or Cloud Run
- [ ] Android `google-services.json` added
- [ ] Kotlin API client URL updated
- [ ] Tested login/register from mobile app

---

## 🆘 Troubleshooting

### "Firebase not initialized" error
→ Check `firebase-key.json` exists and `.env` has correct values

### "Port already in use"
→ Change `PORT` in `.env` or kill process: `lsof -ti:5000 | xargs kill -9`

### "CORS error" on mobile
→ Update `CLIENT_URL` in `.env`

### "Firebase auth failed"
→ Make sure service account JSON is valid

---

**You're ready to go! 🚀**
