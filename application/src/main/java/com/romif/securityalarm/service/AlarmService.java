package com.romif.securityalarm.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.romif.securityalarm.domain.*;
import com.romif.securityalarm.repository.AlarmRepository;
import com.romif.securityalarm.repository.DeviceRepository;
import com.romif.securityalarm.repository.UserRepository;
import com.romif.securityalarm.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

@Service
public class AlarmService {

    private final Logger log = LoggerFactory.getLogger(AlarmService.class);

    @Autowired
    private StatusService statusService;

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private MailService mailService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private Cache<String, Alarm> emailsMoving = CacheBuilder.newBuilder().expireAfterWrite(2, TimeUnit.HOURS).build();
    private Cache<Alarm, Object> emailsInaccessible = CacheBuilder.newBuilder().expireAfterWrite(2, TimeUnit.HOURS).build();


    @Scheduled(cron = "* * * * * *")
    public void checkStatuses() {
        ZonedDateTime now = ZonedDateTime.now();


        alarmRepository.findAll().stream().filter(alarm -> !alarm.isPaused()).forEach(alarm -> {


            String login = alarm.getDevice().getLogin();
            if (alarm.getTrackingTypes().contains(TrackingType.INACCESSIBILITY)) {
                Optional<Status> status = statusService.getLastStatusCreatedBy(login);
                log.debug(status.toString());

                if (status.isPresent() && status.get().getCreatedDate().plusMinutes(2).isBefore(now)) {
                    log.warn("ALARM!! Device is inaccessible");

                    if (alarm.getNotificationTypes().contains(NotificationType.EMAIL)) {
                        if (emailsInaccessible.getIfPresent(alarm) == null) {
                            emailsInaccessible.put(alarm, new Object());
                            log.error("ALARM!! Device is inaccessible. Sending email");
                            Optional<User> user =  userRepository.findOneByLogin(alarm.getCreatedBy());

                            if (user.isPresent()) {
                                mailService.sendDeviceInaccessibleAlertEmail(user.get(), status.get());
                            }

                        }
                    }


                }

            }

            if (alarm.getTrackingTypes().contains(TrackingType.MOVEMENT)) {
                Queue<Status> statuses = statusService.getLast10StatusesCreatedBy(login);

                statuses.forEach(status -> log.debug(status.toString()));

                if (isDeviceMoving(statuses)) {

                    if (alarm.getNotificationTypes().contains(NotificationType.EMAIL)) {
                        if (emailsMoving.getIfPresent(login) == null) {
                            emailsMoving.put(login, alarm);
                            log.error("ALARM!! Device is moving. Sending email");
                            Optional<User> user =  userRepository.findOneByLogin(alarm.getCreatedBy());

                            if (user.isPresent()) {
                                mailService.sendDeviceMovingAlertEmail(user.get(), statuses.element());
                            }

                        }
                    }

                    log.debug("ALARM!! Device is moving");
                }


            }


        });



    }

    private boolean isDeviceMoving(Queue<Status> statuses) {
        return true;
    }

    public boolean pauseAlarm(String pauseToken) {
        String deviceLogin = SecurityUtils.getCurrentUserLogin();
        Optional<Device> device = deviceRepository.findOneByLogin(deviceLogin);

        if (device.isPresent() && passwordEncoder.matches(pauseToken, device.get().getPauseToken())) {
            return alarmRepository.findOneByDeviceLogin(deviceLogin).map(alarm -> {
                alarm.setPaused(true);
                alarmRepository.save(alarm);
                return true;
            }).orElse(false);
        } else {
            log.warn("Pause token invalid for device: {}", device);
            return false;
        }

    }

    public boolean resumeAlarm() {
        String deviceLogin = SecurityUtils.getCurrentUserLogin();
        return alarmRepository.findOneByDeviceLogin(deviceLogin).map(alarm -> {
            alarm.setPaused(false);
            alarmRepository.save(alarm);
            return true;
        }).orElse(false);
    }

}
