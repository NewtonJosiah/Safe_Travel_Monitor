const mongoose = require('mongoose');

const locationSchema = new mongoose.Schema({
  journey: {
    type: mongoose.Schema.Types.ObjectId,
    ref: 'Journey',
    required: true
  },
  user: {
    type: mongoose.Schema.Types.ObjectId,
    ref: 'User',
    required: true
  },
  latitude: {
    type: Number,
    required: true
  },
  longitude: {
    type: Number,
    required: true
  },
  accuracy: {
    type: Number,
    default: 0
  },
  altitude: Number,
  speed: Number,
  heading: Number,
  address: String,
  timestamp: {
    type: Date,
    default: Date.now,
    index: true
  }
});

// TTL Index - Auto delete locations after 30 days
locationSchema.index({ timestamp: 1 }, { expireAfterSeconds: 2592000 });

// Compound index for journey location queries
locationSchema.index({ journey: 1, timestamp: -1 });

module.exports = mongoose.model('Location', locationSchema);
