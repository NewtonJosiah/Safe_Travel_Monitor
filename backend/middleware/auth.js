const jwt = require('jsonwebtoken');
const User = require('../models/User');

// Verify JWT Token
const verifyToken = (req, res, next) => {
  const token = req.headers.authorization?.split(' ')[1];

  if (!token) {
    return res.status(401).json({ message: 'No token provided' });
  }

  try {
    const decoded = jwt.verify(token, process.env.JWT_SECRET);
    req.user = decoded;
    next();
  } catch (error) {
    return res.status(401).json({ message: 'Invalid or expired token' });
  }
};

// Check if user is traveler
const isTraveler = (req, res, next) => {
  if (req.user.role !== 'traveler' && req.user.role !== 'both') {
    return res.status(403).json({ message: 'Traveler role required' });
  }
  next();
};

// Check if user is monitor
const isMonitor = (req, res, next) => {
  if (req.user.role !== 'monitor' && req.user.role !== 'both') {
    return res.status(403).json({ message: 'Monitor role required' });
  }
  next();
};

module.exports = { verifyToken, isTraveler, isMonitor };
