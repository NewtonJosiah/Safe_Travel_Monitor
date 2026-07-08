const mongoose = require('mongoose');
const { v4: uuidv4 } = require('uuid');

const journeySchema = new mongoose.Schema({
  id: {
    type: String,
    default: uuidv4,
    unique: true
  },
  traveler: {
    type: mongoose.Schema.Types.ObjectId,
    ref: 'User',
    required: true
  },
  monitors: [{
    type: mongoose.Schema.Types.ObjectId,
    ref: 'User'
  }],
  destination: {
    address: String,
    latitude: Number,
    longitude: Number
  },
  startLocation: {
    address: String,
    latitude: Number,
    longitude: Number
  },
  startTime: {
    type: Date,
    required: true
  },
  estimatedArrivalTime: {
    type: Date,
    required: true
  },
  actualArrivalTime: Date,
  status: {
    type: String,
    enum: ['active', 'completed', 'cancelled', 'overdue'],
    default: 'active'
  },
  emergencyAlerts: [{
    timestamp: Date,
    reason: String,
    location: {
      latitude: Number,
      longitude: Number
    }
  }],
  locationHistory: [{
    latitude: Number,
    longitude: Number,
    accuracy: Number,
    timestamp: { type: Date, default: Date.now }
  }],
  notes: String,
  createdAt: {
    type: Date,
    default: Date.now
  },
  updatedAt: {
    type: Date,
    default: Date.now
  }
});

// Index for faster queries
journeySchema.index({ traveler: 1, status: 1 });
journeySchema.index({ 'monitors': 1, status: 1 });
journeySchema.index({ createdAt: -1 });

module.exports = mongoose.model('Journey', journeySchema);
