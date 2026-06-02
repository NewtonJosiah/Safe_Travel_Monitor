# Firebase Setup Guide - Safe Travel Monitor Backend

Complete guide to switch from MongoDB to Firebase Realtime Database (Free tier includes 100 connections, no credit card needed).

---

## 1️⃣ Firebase Setup

### Step 1: Create Firebase Project

1. Go to [firebase.google.com](https://firebase.google.com)
2. Click "Get Started"
3. Click "Create a project"
4. Project name: `safe-travel-monitor`
5. Uncheck "Enable Google Analytics" (optional)
6. Click "Create project"
7. Wait for project to be created

### Step 2: Enable Realtime Database

1. In Firebase Console, go to **Build > Realtime Database**
2. Click **"Create Database"**
3. Select region: `closest to Africa` (e.g., `eur3` for Europe or `asia-southeast1`)
4. Start in **Test Mode** (free tier)
5. Click **"Enable"**

### Step 3: Get Firebase Credentials

1. Go to **Project Settings** (gear icon)
2. Click **"Service Accounts"** tab
3. Click **"Generate New Private Key"**
4. Save the JSON file as `firebase-key.json` in your backend folder
5. **Never commit this file to GitHub!** Add to `.gitignore`

### Step 4: Get Database URL

1. Go to **Realtime Database**
2. Copy your database URL (looks like: `https://safe-travel-monitor.firebaseio.com`)
3. Add to `.env`:

```env
FIREBASE_PROJECT_ID=your-project-id
FIREBASE_PRIVATE_KEY_ID=from-json-file
FIREBASE_PRIVATE_KEY=from-json-file
FIREBASE_CLIENT_EMAIL=from-json-file
FIREBASE_CLIENT_ID=from-json-file
FIREBASE_DATABASE_URL=https://safe-travel-monitor.firebaseio.com
```

---

## 2️⃣ Update Backend to Use Firebase

### Step 1: Install Firebase Admin SDK

```bash
cd backend
npm install firebase-admin
```

### Step 2: Create Firebase Config

Create `backend/config/firebase.js`:

```javascript
const admin = require('firebase-admin');
const path = require('path');
require('dotenv').config();

const serviceAccount = require('../firebase-key.json');

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: process.env.FIREBASE_DATABASE_URL
});

const db = admin.database();
const auth = admin.auth();

module.exports = { db, auth, admin };
```

### Step 3: Create Firebase Models

Create `backend/models/firebase/User.js`:

```javascript
const { db } = require('../../config/firebase');
const bcrypt = require('bcryptjs');

class User {
  static async create(userData) {
    const userId = db.ref('users').push().key;
    const hashedPassword = await bcrypt.hash(userData.password, 10);
    
    const newUser = {
      ...userData,
      password: hashedPassword,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };

    await db.ref(`users/${userId}`).set(newUser);
    return { id: userId, ...newUser };
  }

  static async findByEmail(email) {
    const snapshot = await db.ref('users').orderByChild('email').equalTo(email).once('value');
    const users = snapshot.val();
    
    if (!users) return null;
    
    const userId = Object.keys(users)[0];
    return { id: userId, ...users[userId] };
  }

  static async findById(userId) {
    const snapshot = await db.ref(`users/${userId}`).once('value');
    const user = snapshot.val();
    
    if (!user) return null;
    return { id: userId, ...user };
  }

  static async update(userId, updates) {
    updates.updatedAt = new Date().toISOString();
    await db.ref(`users/${userId}`).update(updates);
    return { id: userId, ...updates };
  }

  static async delete(userId) {
    await db.ref(`users/${userId}`).remove();
  }

  static async addFriend(userId, friendId) {
    await db.ref(`users/${userId}/friends/${friendId}`).set(true);
  }

  static async removeFriend(userId, friendId) {
    await db.ref(`users/${userId}/friends/${friendId}`).remove();
  }
}

module.exports = User;
```

Create `backend/models/firebase/Journey.js`:

```javascript
const { db } = require('../../config/firebase');
const { v4: uuidv4 } = require('uuid');

class Journey {
  static async create(journeyData) {
    const journeyId = uuidv4();
    
    const newJourney = {
      ...journeyData,
      id: journeyId,
      status: 'active',
      locationHistory: {},
      emergencyAlerts: {},
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };

    await db.ref(`journeys/${journeyId}`).set(newJourney);
    return newJourney;
  }

  static async findById(journeyId) {
    const snapshot = await db.ref(`journeys/${journeyId}`).once('value');
    return snapshot.val();
  }

  static async findByTraveler(travelerId) {
    const snapshot = await db.ref('journeys')
      .orderByChild('traveler')
      .equalTo(travelerId)
      .once('value');
    
    const journeys = snapshot.val();
    return journeys ? Object.values(journeys) : [];
  }

  static async findByMonitor(monitorId) {
    const snapshot = await db.ref('journeys').once('value');
    const allJourneys = snapshot.val() || {};
    
    return Object.values(allJourneys).filter(journey => 
      journey.monitors && journey.monitors[monitorId]
    );
  }

  static async update(journeyId, updates) {
    updates.updatedAt = new Date().toISOString();
    await db.ref(`journeys/${journeyId}`).update(updates);
  }

  static async addLocation(journeyId, latitude, longitude, accuracy) {
    const locationId = db.ref(`journeys/${journeyId}/locationHistory`).push().key;
    
    const location = {
      id: locationId,
      latitude,
      longitude,
      accuracy,
      timestamp: new Date().toISOString()
    };

    await db.ref(`journeys/${journeyId}/locationHistory/${locationId}`).set(location);
    return location;
  }

  static async addEmergencyAlert(journeyId, reason, latitude, longitude) {
    const alertId = db.ref(`journeys/${journeyId}/emergencyAlerts`).push().key;
    
    const alert = {
      id: alertId,
      reason,
      latitude,
      longitude,
      timestamp: new Date().toISOString()
    };

    await db.ref(`journeys/${journeyId}/emergencyAlerts/${alertId}`).set(alert);
    return alert;
  }

  static async delete(journeyId) {
    await db.ref(`journeys/${journeyId}`).remove();
  }
}

module.exports = Journey;
```

Create `backend/models/firebase/Notification.js`:

```javascript
const { db } = require('../../config/firebase');
const { v4: uuidv4 } = require('uuid');

class Notification {
  static async create(notificationData) {
    const notificationId = uuidv4();
    
    const newNotification = {
      ...notificationData,
      id: notificationId,
      isRead: false,
      createdAt: new Date().toISOString()
    };

    await db.ref(`notifications/${notificationId}`).set(newNotification);
    
    // Also add to user's notification list
    await db.ref(`users/${notificationData.recipient}/notifications/${notificationId}`).set(true);
    
    return newNotification;
  }

  static async getForUser(userId) {
    const snapshot = await db.ref(`users/${userId}/notifications`).once('value');
    const notificationIds = snapshot.val() || {};
    
    const notifications = [];
    for (const notificationId of Object.keys(notificationIds)) {
      const notifSnapshot = await db.ref(`notifications/${notificationId}`).once('value');
      notifications.push(notifSnapshot.val());
    }
    
    return notifications.sort((a, b) => 
      new Date(b.createdAt) - new Date(a.createdAt)
    );
  }

  static async markAsRead(notificationId) {
    await db.ref(`notifications/${notificationId}`).update({ isRead: true, readAt: new Date().toISOString() });
  }

  static async delete(notificationId) {
    const notification = await db.ref(`notifications/${notificationId}`).once('value');
    const notifData = notification.val();
    
    if (notifData) {
      await db.ref(`users/${notifData.recipient}/notifications/${notificationId}`).remove();
    }
    
    await db.ref(`notifications/${notificationId}`).remove();
  }
}

module.exports = Notification;
```

Create `backend/models/firebase/Location.js`:

```javascript
const { db } = require('../../config/firebase');
const { v4: uuidv4 } = require('uuid');

class Location {
  static async create(locationData) {
    const locationId = uuidv4();
    
    const newLocation = {
      ...locationData,
      id: locationId,
      timestamp: new Date().toISOString()
    };

    await db.ref(`locations/${locationId}`).set(newLocation);
    return newLocation;
  }

  static async getByJourney(journeyId) {
    const snapshot = await db.ref('locations')
      .orderByChild('journey')
      .equalTo(journeyId)
      .once('value');
    
    const locations = snapshot.val() || {};
    return Object.values(locations).sort((a, b) => 
      new Date(b.timestamp) - new Date(a.timestamp)
    );
  }

  static async getLatestByJourney(journeyId) {
    const locations = await this.getByJourney(journeyId);
    return locations.length > 0 ? locations[0] : null;
  }
}

module.exports = Location;
```

### Step 4: Update Routes to Use Firebase

Update `backend/routes/auth.js`:

```javascript
const express = require('express');
const jwt = require('jsonwebtoken');
const { body, validationResult } = require('express-validator');
const User = require('../models/firebase/User');
const { verifyToken } = require('../middleware/auth');
const bcrypt = require('bcryptjs');

const router = express.Router();

// Register
router.post('/register', [
  body('email').isEmail().normalizeEmail(),
  body('password').isLength({ min: 6 }),
  body('name').notEmpty(),
  body('phone').isMobilePhone()
], async (req, res) => {
  const errors = validationResult(req);
  if (!errors.isEmpty()) {
    return res.status(400).json({ errors: errors.array() });
  }

  try {
    const { name, email, password, phone, role } = req.body;

    // Check if user exists
    const existingUser = await User.findByEmail(email);
    if (existingUser) {
      return res.status(400).json({ message: 'User already exists' });
    }

    // Create new user
    const user = await User.create({
      name,
      email,
      password,
      phone,
      role: role || 'both',
      friends: {},
      emergencyContacts: {},
      preferences: {
        notificationsEnabled: true,
        smsAlerts: true,
        emailAlerts: true,
        overdueThresholdMinutes: 15
      }
    });

    // Create JWT token
    const token = jwt.sign(
      { id: user.id, role: user.role, email: user.email },
      process.env.JWT_SECRET,
      { expiresIn: process.env.JWT_EXPIRE || '7d' }
    );

    res.status(201).json({
      message: 'User registered successfully',
      token,
      user: {
        id: user.id,
        name: user.name,
        email: user.email,
        role: user.role
      }
    });
  } catch (error) {
    console.error(error);
    res.status(500).json({ message: 'Server error' });
  }
});

// Login
router.post('/login', [
  body('email').isEmail(),
  body('password').exists()
], async (req, res) => {
  const errors = validationResult(req);
  if (!errors.isEmpty()) {
    return res.status(400).json({ errors: errors.array() });
  }

  try {
    const { email, password } = req.body;

    // Find user
    const user = await User.findByEmail(email);
    if (!user) {
      return res.status(401).json({ message: 'Invalid credentials' });
    }

    // Check password
    const isPasswordValid = await bcrypt.compare(password, user.password);
    if (!isPasswordValid) {
      return res.status(401).json({ message: 'Invalid credentials' });
    }

    // Create JWT token
    const token = jwt.sign(
      { id: user.id, role: user.role, email: user.email },
      process.env.JWT_SECRET,
      { expiresIn: process.env.JWT_EXPIRE || '7d' }
    );

    res.json({
      message: 'Login successful',
      token,
      user: {
        id: user.id,
        name: user.name,
        email: user.email,
        role: user.role
      }
    });
  } catch (error) {
    console.error(error);
    res.status(500).json({ message: 'Server error' });
  }
});

// Get current user
router.get('/me', verifyToken, async (req, res) => {
  try {
    const user = await User.findById(req.user.id);
    res.json({ user });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

// Update profile
router.put('/update', verifyToken, async (req, res) => {
  try {
    const { name, phone, preferences } = req.body;
    await User.update(req.user.id, { name, phone, preferences });
    const user = await User.findById(req.user.id);

    res.json({ message: 'Profile updated', user });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

module.exports = router;
```

Update `backend/routes/journeys.js`:

```javascript
const express = require('express');
const { body, validationResult } = require('express-validator');
const Journey = require('../models/firebase/Journey');
const Notification = require('../models/firebase/Notification');
const { isTraveler, isMonitor } = require('../middleware/auth');

const router = express.Router();

// Create journey
router.post('/', isTraveler, [
  body('destination').notEmpty(),
  body('estimatedArrivalTime').isISO8601()
], async (req, res) => {
  const errors = validationResult(req);
  if (!errors.isEmpty()) {
    return res.status(400).json({ errors: errors.array() });
  }

  try {
    const { destination, estimatedArrivalTime, monitors, startLocation, notes } = req.body;

    const journey = await Journey.create({
      traveler: req.user.id,
      destination,
      startLocation,
      startTime: new Date().toISOString(),
      estimatedArrivalTime,
      monitors: monitors ? Object.fromEntries(monitors.map(m => [m, true])) : {},
      notes
    });

    // Notify monitors
    const io = req.app.locals.io;
    for (const monitorId of Object.keys(journey.monitors || {})) {
      await Notification.create({
        recipient: monitorId,
        journey: journey.id,
        type: 'JOURNEY_STARTED',
        title: 'Journey Started',
        message: `Journey to ${destination.address} started`,
        priority: 'high'
      });

      if (io) {
        io.to(`user_${monitorId}`).emit('journey_started', journey);
      }
    }

    res.status(201).json({ message: 'Journey created', journey });
  } catch (error) {
    console.error(error);
    res.status(500).json({ message: 'Server error' });
  }
});

// Get active journeys for monitor
router.get('/monitoring', isMonitor, async (req, res) => {
  try {
    const journeys = await Journey.findByMonitor(req.user.id);
    const activeJourneys = journeys.filter(j => j.status === 'active' || j.status === 'overdue');

    res.json({ journeys: activeJourneys });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

// Get journey details
router.get('/:journeyId', async (req, res) => {
  try {
    const journey = await Journey.findById(req.params.journeyId);

    if (!journey) {
      return res.status(404).json({ message: 'Journey not found' });
    }

    // Check authorization
    const isOwner = journey.traveler === req.user.id;
    const isMonitor = journey.monitors && journey.monitors[req.user.id];

    if (!isOwner && !isMonitor) {
      return res.status(403).json({ message: 'Not authorized' });
    }

    res.json({ journey });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

// Update journey status
router.patch('/:journeyId/status', isTraveler, async (req, res) => {
  try {
    const { status } = req.body;
    await Journey.update(req.params.journeyId, {
      status,
      actualArrivalTime: status === 'completed' ? new Date().toISOString() : undefined
    });

    const journey = await Journey.findById(req.params.journeyId);

    // Notify monitors
    const io = req.app.locals.io;
    for (const monitorId of Object.keys(journey.monitors || {})) {
      await Notification.create({
        recipient: monitorId,
        journey: journey.id,
        type: `${status.toUpperCase()}_ALERT`,
        title: status === 'completed' ? 'Safe Arrival' : 'Journey Update',
        message: `Journey status updated to ${status}`,
        priority: status === 'overdue' ? 'critical' : 'normal'
      });

      if (io) {
        io.to(`user_${monitorId}`).emit('journey_updated', { journeyId: journey.id, status });
      }
    }

    res.json({ message: 'Journey updated', journey });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

module.exports = router;
```

Update `backend/routes/locations.js`:

```javascript
const express = require('express');
const { body, validationResult } = require('express-validator');
const Location = require('../models/firebase/Location');
const Journey = require('../models/firebase/Journey');
const { isTraveler } = require('../middleware/auth');

const router = express.Router();

// Submit location update
router.post('/', isTraveler, [
  body('journeyId').notEmpty(),
  body('latitude').isFloat(),
  body('longitude').isFloat()
], async (req, res) => {
  const errors = validationResult(req);
  if (!errors.isEmpty()) {
    return res.status(400).json({ errors: errors.array() });
  }

  try {
    const { journeyId, latitude, longitude, accuracy, altitude, speed, heading, address } = req.body;

    // Verify journey exists and user is traveler
    const journey = await Journey.findById(journeyId);
    if (!journey) {
      return res.status(404).json({ message: 'Journey not found' });
    }

    if (journey.traveler !== req.user.id) {
      return res.status(403).json({ message: 'Not authorized' });
    }

    // Save location
    const location = await Location.create({
      journey: journeyId,
      user: req.user.id,
      latitude,
      longitude,
      accuracy,
      altitude,
      speed,
      heading,
      address
    });

    // Update journey location history
    await Journey.addLocation(journeyId, latitude, longitude, accuracy);

    // Emit real-time update to monitors
    const io = req.app.locals.io;
    if (io) {
      for (const monitorId of Object.keys(journey.monitors || {})) {
        io.to(`user_${monitorId}`).emit('location_update', {
          journeyId,
          latitude,
          longitude,
          accuracy,
          timestamp: new Date().toISOString(),
          address
        });
      }
    }

    res.status(201).json({ message: 'Location updated', location });
  } catch (error) {
    console.error(error);
    res.status(500).json({ message: 'Server error' });
  }
});

// Get journey location history
router.get('/journey/:journeyId', async (req, res) => {
  try {
    const locations = await Location.getByJourney(req.params.journeyId);
    res.json({ locations });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

// Get latest location for journey
router.get('/journey/:journeyId/latest', async (req, res) => {
  try {
    const location = await Location.getLatestByJourney(req.params.journeyId);

    if (!location) {
      return res.status(404).json({ message: 'No location data' });
    }

    res.json({ location });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

module.exports = router;
```

### Step 5: Update `.env`

```env
PORT=5000
NODE_ENV=development

# JWT
JWT_SECRET=your_super_secret_jwt_key_change_this
JWT_EXPIRE=7d

# CORS
CLIENT_URL=http://localhost:3000

# Firebase
FIREBASE_PROJECT_ID=your-project-id
FIREBASE_PRIVATE_KEY_ID=from-json-file
FIREBASE_PRIVATE_KEY=from-json-file (replace \n with actual newlines)
FIREBASE_CLIENT_EMAIL=from-json-file
FIREBASE_CLIENT_ID=from-json-file
FIREBASE_DATABASE_URL=https://safe-travel-monitor.firebaseio.com
```

### Step 6: Update `.gitignore`

```
# Firebase key file
firebase-key.json

# Dependencies
node_modules/
npm-debug.log*

# Environment
.env
.env.local

# IDE
.vscode/
.idea/
*.swp
```

---

## 3️⃣ Firebase Security Rules

In Firebase Console, go to **Realtime Database > Rules** and replace with:

```json
{
  "rules": {
    "users": {
      "$uid": {
        ".read": "$uid === auth.uid",
        ".write": "$uid === auth.uid",
        ".validate": "newData.hasChildren(['name', 'email', 'phone', 'password', 'role'])"
      }
    },
    "journeys": {
      "$journeyId": {
        ".read": "root.child('journeys').child($journeyId).child('traveler').val() === auth.uid || root.child('journeys').child($journeyId).child('monitors').hasChild(auth.uid)",
        ".write": "root.child('journeys').child($journeyId).child('traveler').val() === auth.uid"
      }
    },
    "locations": {
      "$locationId": {
        ".read": true,
        ".write": "auth != null"
      }
    },
    "notifications": {
      "$notificationId": {
        ".read": "root.child('notifications').child($notificationId).child('recipient').val() === auth.uid",
        ".write": false
      }
    }
  }
}
```

---

## 4️⃣ Deploy to Firebase Hosting

### Option A: Firebase Hosting (Free)

```bash
# Install Firebase CLI
npm install -g firebase-tools

# Login
firebase login

# Initialize Firebase in your project
firebase init hosting

# Deploy
firebase deploy
```

### Option B: Google Cloud Run (Free tier)

Create `Dockerfile`:
```dockerfile
FROM node:18-alpine
WORKDIR /app
COPY package*.json ./
COPY firebase-key.json ./
RUN npm install --production
COPY . .
EXPOSE 5000
CMD ["node", "server.js"]
```

Deploy:
```bash
gcloud run deploy safe-travel-backend \
  --source . \
  --platform managed \
  --region africa-south1 \
  --allow-unauthenticated
```

### Option C: Render.com (Free tier)

1. Push code to GitHub
2. Go to [render.com](https://render.com)
3. Connect GitHub repo
4. Create new Web Service
5. Select "Node"
6. Add environment variables
7. Deploy

---

## 5️⃣ Kotlin Integration with Firebase

### Step 1: Add Firebase Dependencies

Update `app/build.gradle`:

```gradle
dependencies {
    // Existing...
    
    // Firebase
    implementation platform('com.google.firebase:firebase-bom:32.2.3')
    implementation 'com.google.firebase:firebase-database-ktx'
    implementation 'com.google.firebase:firebase-auth-ktx'
    implementation 'com.google.firebase:firebase-messaging-ktx'
    
    // Retrofit
    implementation 'com.squareup.retrofit2:retrofit:2.9.0'
    implementation 'com.squareup.retrofit2:converter-gson:2.9.0'
}
```

### Step 2: Update API Client

The API client remains the same, but now points to your Firebase backend URL.

---

## 6️⃣ Testing

```bash
cd backend
npm run dev

# Should see:
# Server running on port 5000
# Firebase initialized
```

Test with curl:
```bash
curl -X POST http://localhost:5000/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Test User","email":"test@example.com","password":"password123","phone":"+1234567890"}'
```

---

## ✅ Advantages of Firebase

✅ **Free tier** - 100 concurrent connections
✅ **No credit card required** - Truly free
✅ **Works in Africa** - Google infrastructure available
✅ **Real-time database** - Perfect for live tracking
✅ **Built-in authentication** - OAuth support
✅ **Auto-scaling** - Handles growth
✅ **Global CDN** - Fast worldwide access
✅ **Push notifications** - Firebase Cloud Messaging (FCM)

---

## ❌ To Remove MongoDB

1. Delete `backend/models/User.js`, `Journey.js`, `Location.js`, `Notification.js`
2. Update all route files to use Firebase models
3. Remove MongoDB from `.env`
4. Run `npm uninstall mongoose`

---

You now have a fully functional Firebase backend! 🎉

