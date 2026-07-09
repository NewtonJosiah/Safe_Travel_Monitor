const express = require('express');
const { body, validationResult } = require('express-validator');
const Location = require('../models/Location');
const Journey = require('../models/Journey');
const { verifyToken, isTraveler } = require('../middleware/auth');

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

    if (journey.traveler.toString() !== req.user.id) {
      return res.status(403).json({ message: 'Not authorized' });
    }

    // Save location
    const location = new Location({
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

    await location.save();

    // Update journey location history
    journey.locationHistory.push({
      latitude,
      longitude,
      accuracy,
      timestamp: new Date()
    });
    await journey.save();

    // Emit real-time update to monitors
    const io = req.app.locals.io;
    if (io) {
      journey.monitors.forEach(monitorId => {
        io.to(`user_${monitorId}`).emit('location_update', {
          journeyId,
          latitude,
          longitude,
          accuracy,
          timestamp: new Date(),
          address
        });
      });
    }

    res.status(201).json({ message: 'Location updated', location });
  } catch (error) {
    console.error(error);
    res.status(500).json({ message: 'Server error' });
  }
});

// Get journey location history
router.get('/journey/:journeyId', verifyToken, async (req, res) => {
  try {
    const journey = await Journey.findById(req.params.journeyId);
    if (!journey) {
      return res.status(404).json({ message: 'Journey not found' });
    }

    // Check authorization: traveler or a monitor of this journey
    const isOwner = journey.traveler.toString() === req.user.id;
    const isMonitor = journey.monitors.some(m => m.toString() === req.user.id);

    if (!isOwner && !isMonitor) {
      return res.status(403).json({ message: 'Not authorized to view this location history' });
    }

    const locations = await Location.find({ journey: req.params.journeyId })
      .sort({ timestamp: -1 })
      .limit(100);

    res.json({ locations });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

// Get latest location for journey
router.get('/journey/:journeyId/latest', verifyToken, async (req, res) => {
  try {
    const journey = await Journey.findById(req.params.journeyId);
    if (!journey) {
      return res.status(404).json({ message: 'Journey not found' });
    }

    // Check authorization: traveler or a monitor of this journey
    const isOwner = journey.traveler.toString() === req.user.id;
    const isMonitor = journey.monitors.some(m => m.toString() === req.user.id);

    if (!isOwner && !isMonitor) {
      return res.status(403).json({ message: 'Not authorized to view this location' });
    }

    const location = await Location.findOne({ journey: req.params.journeyId })
      .sort({ timestamp: -1 });

    if (!location) {
      return res.status(404).json({ message: 'No location data' });
    }

    res.json({ location });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

module.exports = router;
