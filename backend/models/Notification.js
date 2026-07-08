const mongoose = require('mongoose');
const { v4: uuidv4 } = require('uuid');

const notificationSchema = new mongoose.Schema({
  id: {
    type: String,
    default: uuidv4,
    unique: true
  },
  recipient: {
    type: mongoose.Schema.Types.ObjectId,
    ref: 'User',
    required: true
  },
  journey: {
    type: mongoose.Schema.Types.ObjectId,
    ref: 'Journey'
  },
  type: {
    type: String,
    enum: [
      'JOURNEY_STARTED',
      'LOCATION_UPDATE',
      'EMERGENCY_ALERT',
      'OVERDUE_ALERT',
      'SAFE_ARRIVAL',
      'JOURNEY_CANCELLED',
      'FRIEND_REQUEST'
    ],
    required: true
  },
  title: {
    type: String,
    required: true
  },
  message: {
    type: String,
    required: true
  },
  data: {
    travelerName: String,
    travelerId: mongoose.Schema.Types.ObjectId,
    destination: String,
    currentLocation: {
      latitude: Number,
      longitude: Number
    },
    eta: Date
  },
  isRead: {
    type: Boolean,
    default: false
  },
  readAt: Date,
  priority: {
    type: String,
    enum: ['low', 'normal', 'high', 'critical'],
    default: 'normal'
  },
  createdAt: {
    type: Date,
    default: Date.now,
    index: true
  },
  expiresAt: {
    type: Date,
    default: () => new Date(+new Date() + 30 * 24 * 60 * 60 * 1000)
  }
});

// TTL Index - Auto delete notifications after 30 days
notificationSchema.index({ expiresAt: 1 }, { expireAfterSeconds: 0 });

// Index for user notifications
notificationSchema.index({ recipient: 1, createdAt: -1 });

module.exports = mongoose.model('Notification', notificationSchema);
