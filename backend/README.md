# Safe Travel Monitor - Backend Server

Node.js + Express backend for the Safe Travel Monitor application, providing real-time location tracking, journey management, and emergency alerts.

## Features

- **User Authentication**: JWT-based registration and login
- **Journey Management**: Create, track, and manage journeys
- **Real-time Location Tracking**: WebSocket-based live location updates
- **Emergency Alerts**: Critical alerts with immediate notification
- **Overdue Monitoring**: Automatic alerts when traveler misses ETA
- **Notifications**: Push notifications and notification history
- **Multi-role Support**: Travelers and monitors with specific permissions

## Prerequisites

- Node.js (v14 or higher)
- MongoDB (local or cloud-based)
- npm or yarn

## Installation

1. Install dependencies:
```bash
cd backend
npm install
```

2. Create `.env` file from `.env.example`:
```bash
cp .env.example .env
```

3. Update `.env` with your configuration:
```env
PORT=5000
MONGODB_URI=mongodb://localhost:27017/safe-travel-monitor
JWT_SECRET=your_secret_key_here
CLIENT_URL=http://localhost:3000
```

4. Start MongoDB (if running locally):
```bash
mongod
```

## Running the Server

### Development
```bash
npm run dev
```

### Production
```bash
npm start
```

Server will run on `http://localhost:5000`

## API Endpoints

### Authentication
- `POST /api/auth/register` - Register new user
- `POST /api/auth/login` - Login user
- `GET /api/auth/me` - Get current user profile
- `PUT /api/auth/update` - Update user profile
- `POST /api/auth/device-token` - Register device token for push notifications

### Journeys
- `POST /api/journeys` - Create new journey
- `GET /api/journeys/monitoring` - Get journeys user is monitoring
- `GET /api/journeys/:journeyId` - Get journey details
- `PATCH /api/journeys/:journeyId/status` - Update journey status
- `GET /api/journeys/user/:userId/history` - Get user's journey history

### Locations
- `POST /api/locations` - Submit location update
- `GET /api/locations/journey/:journeyId` - Get journey location history
- `GET /api/locations/journey/:journeyId/latest` - Get latest location

### Notifications
- `GET /api/notifications` - Get user notifications
- `PATCH /api/notifications/:notificationId/read` - Mark as read
- `PATCH /api/notifications/all/read` - Mark all as read
- `DELETE /api/notifications/:notificationId` - Delete notification

## WebSocket Events

### Client Events (sent from client)
- `user_join` - Join personal user room
- `join_journey` - Join journey room
- `location_update` - Send location update
- `emergency_alert` - Send emergency alert
- `check_overdue` - Check if journey is overdue

### Server Events (received by client)
- `location_updated` - Real-time location update
- `journey_started` - Journey started notification
- `journey_updated` - Journey status update
- `journey_overdue` - Journey overdue alert
- `emergency_alert` - Emergency alert received

## Database Models

### User
- Personal information
- Authentication credentials
- Friends list
- Emergency contacts
- Device tokens for push notifications
- Preferences

### Journey
- Traveler and monitors
- Destination and start location
- Timeline data
- Location history
- Emergency alerts
- Status tracking

### Location
- GPS coordinates
- Accuracy data
- Timestamp
- Address (optional)
- Speed and heading

### Notification
- Recipient and related journey
- Type and priority
- Read status
- Data payload
- Auto-expiration after 30 days

## Architecture

```
backend/
├── models/           # Database schemas
├── routes/           # API endpoints
├── middleware/       # Auth and error handling
├── sockets/          # WebSocket handlers
├── server.js         # Main server file
├── package.json      # Dependencies
└── .env.example      # Configuration template
```

## Environment Variables

See `.env.example` for all available configuration options.

## Error Handling

All errors return appropriate HTTP status codes:
- `400` - Bad Request (validation errors)
- `401` - Unauthorized (authentication required)
- `403` - Forbidden (insufficient permissions)
- `404` - Not Found
- `500` - Server Error

## Security

- Passwords are hashed with bcrypt
- JWT tokens for stateless authentication
- CORS configured for specific origins
- Input validation on all endpoints
- Role-based access control

## Integration with Frontend

### Android (Kotlin)
Update `TravelerActivity.kt` and `MonitorActivity.kt` to use API:
```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("http://your-backend-url/api/")
    .addConverterFactory(GsonConverterFactory.create())
    .build()
```

### Web (HTML/JavaScript)
Update `index.html` JavaScript to use API:
```javascript
const API_URL = 'http://your-backend-url/api';
const socket = io('http://your-backend-url');
```

## Testing

```bash
npm test
```

## Deployment

See deployment guide for hosting on:
- Heroku
- AWS
- Google Cloud
- DigitalOcean
- Azure

## Support

For issues and questions, please open an issue on GitHub.

## License

MIT
