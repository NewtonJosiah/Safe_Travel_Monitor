const mongoose = require('mongoose');
const bcrypt = require('bcryptjs');

const userSchema = new mongoose.Schema({
  name: {
    type: String,
    required: true
  },
  email: {
    type: String,
    required: true,
    unique: true,
    lowercase: true,
    match: [/^\w+([\.-]?\w+)*@\w+([\.-]?\w+)*(\.\w{2,3})+$/, 'Please provide a valid email']
  },
  password: {
    type: String,
    required: true,
    minlength: 6,
    select: false
  },
  phone: {
    type: String,
    required: true
  },
  role: {
    type: String,
    enum: ['traveler', 'monitor', 'both'],
    default: 'both'
  },
  profilePhoto: {
    type: String,
    default: null
  },
  friends: [{
    type: mongoose.Schema.Types.ObjectId,
    ref: 'User'
  }],
  emergencyContacts: [{
    name: String,
    phone: String,
    email: String
  }],
  preferences: {
    notificationsEnabled: { type: Boolean, default: true },
    smsAlerts: { type: Boolean, default: true },
    emailAlerts: { type: Boolean, default: true },
    overdueThresholdMinutes: { type: Number, default: 15 }
  },
  deviceTokens: [{
    token: String,
    platform: { type: String, enum: ['android', 'web', 'ios'] },
    addedAt: { type: Date, default: Date.now }
  }],
  createdAt: {
    type: Date,
    default: Date.now
  },
  updatedAt: {
    type: Date,
    default: Date.now
  }
});

// Hash password before saving
userSchema.pre('save', async function(next) {
  if (!this.isModified('password')) {
    next();
  }

  const salt = await bcrypt.genSalt(10);
  this.password = await bcrypt.hash(this.password, salt);
});

// Match password method
userSchema.methods.matchPassword = async function(enteredPassword) {
  return await bcrypt.compare(enteredPassword, this.password);
};

module.exports = mongoose.model('User', userSchema);
