const { db } = require('../../config/firebase');
const { v4: uuidv4 } = require('uuid');

class Journey {
  static async create(journeyData) {
    try {
      const journeyId = uuidv4();
      
      const newJourney = {
        ...journeyData,
        id: journeyId,
        status: 'active',
        locationHistory: {},
        emergencyAlerts: {},
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString()
      };

      await db.ref(`journeys/${journeyId}`).set(newJourney);
      return newJourney;
    } catch (error) {
      console.error('Error creating journey:', error);
      throw error;
    }
  }

  static async findById(journeyId) {
    try {
      const snapshot = await db.ref(`journeys/${journeyId}`).once('value');
      return snapshot.val();
    } catch (error) {
      console.error('Error finding journey:', error);
      throw error;
    }
  }

  static async findByTraveler(travelerId) {
    try {
      const snapshot = await db.ref('journeys')
        .orderByChild('traveler')
        .equalTo(travelerId)
        .once('value');
      
      const journeys = snapshot.val();
      return journeys ? Object.values(journeys) : [];
    } catch (error) {
      console.error('Error finding journeys by traveler:', error);
      throw error;
    }
  }

  static async findByMonitor(monitorId) {
    try {
      const snapshot = await db.ref('journeys').once('value');
      const allJourneys = snapshot.val() || {};
      
      return Object.values(allJourneys).filter(journey => 
        journey.monitors && journey.monitors[monitorId]
      );
    } catch (error) {
      console.error('Error finding journeys by monitor:', error);
      throw error;
    }
  }

  static async update(journeyId, updates) {
    try {
      updates.updatedAt = new Date().toISOString();
      await db.ref(`journeys/${journeyId}`).update(updates);
    } catch (error) {
      console.error('Error updating journey:', error);
      throw error;
    }
  }

  static async addLocation(journeyId, latitude, longitude, accuracy) {
    try {
      const locationId = db.ref(`journeys/${journeyId}/locationHistory`).push().key;
      
      const location = {
        id: locationId,
        latitude,
        longitude,
        accuracy,
        timestamp: new Date().toISOString()
      };

      await db.ref(`journeys/${journeyId}/locationHistory/${locationId}`).set(location);
      return location;
    } catch (error) {
      console.error('Error adding location:', error);
      throw error;
    }
  }

  static async addEmergencyAlert(journeyId, reason, latitude, longitude) {
    try {
      const alertId = db.ref(`journeys/${journeyId}/emergencyAlerts`).push().key;
      
      const alert = {
        id: alertId,
        reason,
        latitude,
        longitude,
        timestamp: new Date().toISOString()
      };

      await db.ref(`journeys/${journeyId}/emergencyAlerts/${alertId}`).set(alert);
      return alert;
    } catch (error) {
      console.error('Error adding emergency alert:', error);
      throw error;
    }
  }

  static async delete(journeyId) {
    try {
      await db.ref(`journeys/${journeyId}`).remove();
    } catch (error) {
      console.error('Error deleting journey:', error);
      throw error;
    }
  }
}

module.exports = Journey;
