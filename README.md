# Blackout Protocol

Offline SOS Mesh Network for Disaster Situations

## Problem
During floods and disasters, mobile networks and internet connectivity fail,
making it impossible for people to send SOS messages or seek help.

## Solution
Blackout Protocol is an Android application that enables offline SOS messaging
by creating a peer-to-peer mesh network using nearby devices. Messages hop from
phone to phone until one device gains internet access and syncs the data to the cloud.

## How It Works
- Nearby devices discover each other without internet
- SOS messages are forwarded across devices (multi-hop)
- A gateway device uploads messages when internet becomes available
- Messages are synced to Firebase Realtime Database

## Google Technologies Used
- Google Nearby Connections API
- Firebase Realtime Database
- Google Play Services
- Android SDK (Kotlin)

## Key Features
- Works without internet
- Multi-hop SOS message delivery
- Automatic cloud sync via gateway device
- Real-time network status and logs
- Optional encrypted location sharing

## Architecture
Offline Mesh → Gateway Device → Firebase Realtime Database


## Documentation
Detailed project explanation is available in `/docs/Blackout_Protocol_Overview.pdf`[Untitled document-56.pdf](https://github.com/user-attachments/files/24347442/Untitled.document-56.pdf)


## Disclaimer
This project is a prototype developed for educational and hackathon purposes.
