const Journey = require('../models/Journey');
const Notification = require('../models/Notification');

const socketHandlers = (io, socket) => {
  // User joins their personal room
  socket.on('user_join', (userId) => {
    socket.join(`user_${userId}`);
    console.log(`User ${userId} connected via socket`);
  });

  // Join journey room
  socket.on('join_journey', (journeyId, userId) => {
    socket.join(`journey_${journeyId}`);
    console.log(`User ${userId} joined journey ${journeyId}`);
  });

  // Location update from traveler
  socket.on('location_update', async (data) => {
    try {
      const { journeyId, latitude, longitude, accuracy, address } = data;

      // Broadcast to all monitors in this journey
      io.to(`journey_${journeyId}`).emit('location_updated', {
        journeyId,
        latitude,
        longitude,
        accuracy,
        address,
        timestamp: new Date()
      });
    } catch (error) {
      console.error('Location update error:', error);
    }
  });

  // Emergency alert
  socket.on('emergency_alert', async (data) => {
    try {
      const { journeyId, userId, reason } = data;
      const journey = await Journey.findById(journeyId);

      if (!journey) return;

      // Add emergency alert to journey
      journey.emergencyAlerts.push({
        timestamp: new Date(),
        reason,
        location: {
          latitude: data.latitude,
          longitude: data.longitude
        }
      });
      await journey.save();

      // Create critical notifications for all monitors
      const notificationPromises = journey.monitors.map(monitorId =>
        Notification.create({
          recipient: monitorId,
          journey: journeyId,
          type: 'EMERGENCY_ALERT',
          title: '🚨 EMERGENCY ALERT',
          message: `Emergency alert from traveler: ${reason}`,
          priority: 'critical',
          data: {
            travelerId: userId,
            currentLocation: {
              latitude: data.latitude,
              longitude: data.longitude
            }
          }
        })
      );

      await Promise.all(notificationPromises);

      // Broadcast to all monitors
      io.to(`journey_${journeyId}`).emit('emergency_alert', {
        journeyId,
        reason,
        latitude: data.latitude,
        longitude: data.longitude,
        timestamp: new Date()
      });
    } catch (error) {
      console.error('Emergency alert error:', error);
    }
  });

  // Overdue check - sent periodically by client
  socket.on('check_overdue', async (data) => {
    try {
      const { journeyId } = data;
      const journey = await Journey.findById(journeyId);

      if (!journey || journey.status !== 'active') return;

      const now = new Date();
      const eta = new Date(journey.estimatedArrivalTime);

      if (now > eta && journey.status === 'active') {
        // Atomic update to mark as overdue only if it's still active
        const updatedJourney = await Journey.findOneAndUpdate(
          { _id: journeyId, status: 'active' },
          { $set: { status: 'overdue' } },
          { new: true }
        ).populate('monitors');

        if (!updatedJourney) return; // Already updated by another process/client

        // Notify monitors
        const notificationPromises = updatedJourney.monitors.map(monitorId =>
          Notification.create({
            recipient: monitorId,
            journey: journeyId,
            type: 'OVERDUE_ALERT',
            title: '⚠️ Overdue Alert',
            message: 'Traveler has not arrived by estimated time',
            priority: 'critical'
          })
        );

        await Promise.all(notificationPromises);

        // Emit to all monitors in the journey room
        io.to(`journey_${journeyId}`).emit('journey_overdue', { journeyId });
      }
    } catch (error) {
      console.error('Overdue check error:', error);
    }
  });

  // User disconnects
  socket.on('disconnect', () => {
    console.log('Client disconnected:', socket.id);
  });
};

module.exports = socketHandlers;
