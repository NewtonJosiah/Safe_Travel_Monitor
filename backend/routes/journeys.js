const express = require('express');
const { body, validationResult } = require('express-validator');
const Journey = require('../models/Journey');
const Notification = require('../models/Notification');
const { verifyToken, isTraveler, isMonitor } = require('../middleware/auth');

const router = express.Router();

// Create journey
router.post('/', isTraveler, [
  body('destination').notEmpty(),
  body('estimatedArrivalTime').isISO8601(),
  body('monitors').isArray()
], async (req, res) => {
  const errors = validationResult(req);
  if (!errors.isEmpty()) {
    return res.status(400).json({ errors: errors.array() });
  }

  try {
    const { destination, estimatedArrivalTime, monitors, startLocation, notes } = req.body;

    const journey = new Journey({
      traveler: req.user.id,
      destination,
      startLocation,
      startTime: new Date(),
      estimatedArrivalTime: new Date(estimatedArrivalTime),
      monitors,
      notes
    });

    await journey.save();

    // Ensure populate succeeds
    try {
      await journey.populate('traveler', 'name email phone');
      await journey.populate('monitors', 'name email phone');
    } catch (populateError) {
      console.error('Populate error on creation:', populateError);
      // We can still continue, but the notification might have less data
    }

    // Notify monitors
    const io = req.app.locals.io;
    const notificationPromises = monitors.map(monitorId =>
      Notification.create({
        recipient: monitorId,
        journey: journey._id,
        type: 'JOURNEY_STARTED',
        title: 'Journey Started',
        message: `${journey.traveler.name} has started their journey to ${destination.address}`,
        priority: 'high',
        data: {
          travelerName: journey.traveler.name,
          travelerId: journey.traveler._id,
          destination: destination.address,
          eta: estimatedArrivalTime
        }
      })
    );

    await Promise.all(notificationPromises);

    // Emit to connected monitors
    if (io) {
      monitors.forEach(monitorId => {
        io.to(`user_${monitorId}`).emit('journey_started', journey);
      });
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
    const journeys = await Journey.find({
      monitors: req.user.id,
      status: { $in: ['active', 'overdue'] }
    })
      .populate('traveler', 'name email phone profilePhoto')
      .sort({ startTime: -1 });

    res.json({ journeys });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

// Get journey details
router.get('/:journeyId', verifyToken, async (req, res) => {
  try {
    const journey = await Journey.findById(req.params.journeyId)
      .populate('traveler', 'name email phone profilePhoto')
      .populate('monitors', 'name email phone');

    if (!journey) {
      return res.status(404).json({ message: 'Journey not found' });
    }

    // Check authorization
    const isOwner = journey.traveler._id.toString() === req.user.id;
    const isMonitor = journey.monitors.some(m => m._id.toString() === req.user.id);

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
    const journey = await Journey.findByIdAndUpdate(
      req.params.journeyId,
      {
        status,
        actualArrivalTime: status === 'completed' ? new Date() : undefined
      },
      { new: true }
    ).populate('monitors');

    // Notify monitors
    const io = req.app.locals.io;
    const messageMap = {
      'completed': 'Safe Arrival',
      'cancelled': 'Journey Cancelled',
      'overdue': 'Overdue Alert'
    };

    const notificationPromises = journey.monitors.map(monitor =>
      Notification.create({
        recipient: monitor._id,
        journey: journey._id,
        type: `${status.toUpperCase()}_ALERT`,
        title: messageMap[status] || 'Journey Update',
        message: `Journey status updated to ${status}`,
        priority: status === 'overdue' ? 'critical' : 'normal'
      })
    );

    await Promise.all(notificationPromises);

    if (io) {
      journey.monitors.forEach(monitor => {
        io.to(`user_${monitor._id}`).emit('journey_updated', { journeyId: journey._id, status });
      });
    }

    res.json({ message: 'Journey updated', journey });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

// Get journey history
router.get('/user/:userId/history', verifyToken, async (req, res) => {
  try {
    // Only allow users to see their own history
    if (req.user.id !== req.params.userId) {
      return res.status(403).json({ message: 'Not authorized' });
    }

    const page = parseInt(req.query.page) || 1;
    const limit = parseInt(req.query.limit) || 20;
    const skip = (page - 1) * limit;

    const journeys = await Journey.find({
      traveler: req.params.userId,
      status: { $in: ['completed', 'cancelled'] }
    })
      .sort({ startTime: -1 })
      .skip(skip)
      .limit(limit);

    const total = await Journey.countDocuments({
      traveler: req.params.userId,
      status: { $in: ['completed', 'cancelled'] }
    });

    res.json({
      journeys,
      page,
      totalPages: Math.ceil(total / limit),
      totalResults: total
    });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

module.exports = router;
