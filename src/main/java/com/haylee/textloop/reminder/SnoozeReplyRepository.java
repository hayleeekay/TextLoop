package com.haylee.textloop.reminder;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SnoozeReplyRepository extends JpaRepository<SnoozeReply, UUID> {}
