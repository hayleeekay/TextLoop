package com.haylee.textloop.reminder;

import org.springframework.data.jpa.repository.JpaRepository;

interface SnoozeSettingRepository extends JpaRepository<SnoozeSetting, Integer> {}
