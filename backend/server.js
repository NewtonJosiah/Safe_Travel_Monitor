const express = require('express');
const http = require('http');
const socketIo = require('socket.io');
const cors = require('cors');
const dotenv = require('dotenv');
const mongoose = require('mongoose');

// Load environment variables
dotenv.config();

// Import routes
const authRoutes = require('./routes/auth');
const journeyRoutes = require('./routes/journeys');
const locationRoutes = require('./routes/locations');
const notificationRoutes = require('./routes/notifications');

// Import middleware
const errorHandler = require('./middleware/errorHandler');
const { verifyToken } = require('./middleware/auth');

// Import socket handlers
const socketHandlers = require('./sockets/handlers');

const app = express();
const server = http.createServer(app);
const io = socketIo(server, {
  cors: {
    origin: process.env.CLIENT_URL,
    methods: ['GET', 'POST']
  }
});

// Middleware
app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({ status: 'Server is running', timestamp: new Date() });
});

// Routes
app.use('/api/auth', authRoutes);
app.use('/api/journeys', verifyToken, journeyRoutes);
app.use('/api/locations', verifyToken, locationRoutes);
app.use('/api/notifications', verifyToken, notificationRoutes);

// Store io instance for use in routes
app.locals.io = io;

// Socket.IO connection handler
io.on('connection', (socket) => {
  console.log('New client connected:', socket.id);
  socketHandlers(io, socket);
});

// Error handling middleware
app.use(errorHandler);

// 404 handler
app.use((req, res) => {
  res.status(404).json({ message: 'Route not found' });
});

// Database connection
mongoose.connect(process.env.MONGODB_URI || 'mongodb://localhost:27017/safe-travel-monitor')
  .then(() => console.log('MongoDB connected'))
  .catch(err => console.error('MongoDB connection error:', err));

// Start server
const PORT = process.env.PORT || 5000;
server.listen(PORT, () => {
  console.log(`Server running on port ${PORT}`);
});

module.exports = { app, io, server };
