const express = require('express');
const Notification = require('../models/Notification');

const router = express.Router();

// Get user notifications
router.get('/', async (req, res) => {
  try {
    const { unread } = req.query;
    const filter = { recipient: req.user.id };

    if (unread === 'true') {
      filter.isRead = false;
    }

    const notifications = await Notification.find(filter)
      .populate('journey')
      .sort({ createdAt: -1 })
      .limit(50);

    res.json({ notifications });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

// Mark notification as read
router.patch('/:notificationId/read', async (req, res) => {
  try {
    const notification = await Notification.findByIdAndUpdate(
      req.params.notificationId,
      { isRead: true, readAt: new Date() },
      { new: true }
    );

    res.json({ notification });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

// Mark all notifications as read
router.patch('/all/read', async (req, res) => {
  try {
    await Notification.updateMany(
      { recipient: req.user.id, isRead: false },
      { isRead: true, readAt: new Date() }
    );

    res.json({ message: 'All notifications marked as read' });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

// Delete notification
router.delete('/:notificationId', async (req, res) => {
  try {
    await Notification.findByIdAndDelete(req.params.notificationId);
    res.json({ message: 'Notification deleted' });
  } catch (error) {
    res.status(500).json({ message: 'Server error' });
  }
});

module.exports = router;
