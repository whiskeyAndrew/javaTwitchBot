package com.example.TwitchBot.channelChat;

import com.example.TwitchBot.channelChat.internalCommands.DatabaseCommandsHandler;
import com.example.TwitchBot.channelInfo.ChannelFollowers;
import com.example.TwitchBot.config.TwitchClientConfig;
import com.example.TwitchBot.entity.Command;
import com.example.TwitchBot.entity.DuelistStats;
import com.example.TwitchBot.entity.Follower;
import com.example.TwitchBot.entity.Iq;
import com.example.TwitchBot.services.DuelistStatService;
import com.example.TwitchBot.services.FollowerService;
import com.example.TwitchBot.services.IqService;
import com.example.TwitchBot.services.DatabaseCommandsService;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.github.twitch4j.common.enums.CommandPermission;
import com.github.twitch4j.common.events.domain.EventUser;
import com.github.twitch4j.helix.domain.UserList;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Component
@Setter
@Getter
@RequiredArgsConstructor
public class ChatEventHandler extends Thread {


    private final ChannelFollowers channelFollowers;

    //Logic Staff
    private final DatabaseCommandsHandler dataBaseCommandsHandler;
    private final DatabaseCommandsService databaseCommandsService;
    private final TwitchClient twitchClient;
    private final TwitchClientConfig twitchClientConfig;
    private final FollowerService followerService;
    private final DuelistStatService duelistStatService;
    private final static int MESSAGE_TIMEOUT_EVERYONE = 1;
    //Timeout, need to prevent chat spam from bot (maybe not working right now)
    Instant lastMessageTime;

    //Iq checker
    private final IqService iqService;
    private final static int MAX_IQ = 200;
    private final static int MIN_IQ = 3;

    //Duels
    Instant firstDuelistTriesToFindSomeoneTime;
    private DuelistStats duelistOne;
    private DuelistStats duelistTwo;

    @PostConstruct
    private void initStart() throws MalformedURLException, UnsupportedEncodingException {
        firstDuelistTriesToFindSomeoneTime = Instant.EPOCH;
        start();
        lastMessageTime = Instant.now();
    }

    @PreDestroy
    private void appCloseMessage() {
        sendMessageToChatIgnoreTimer("?? ????????");
    }

    @Override
    public void run() {
        System.out.println("STARTED LISTENING CHANNEL");
        twitchClient.getEventManager().onEvent(ChannelMessageEvent.class, event -> {
            System.out.println("[" + event.getChannel().getName() + "][" + event.getPermissions().toString() + "] "
                    + event.getUser().getName() + ": " + event.getMessage());

            //?????????????????? ??????????-???? ??????????, ?????????????????? ???????? ???? ?? ???? ???????????????????? ??????????????
            if (!followerService.isFollowerExistsById(Long.parseLong(event.getUser().getId()))) {
                UserList resultList = twitchClient.getHelix().getUsers(twitchClientConfig.getChannelTokenAccess(), Collections.singletonList(event.getUser().getId()), null).execute();
                resultList.getUsers().forEach(user -> {
                    Follower follower = new Follower(Long.parseLong(user.getId()), user.getDisplayName(), user.getDisplayName(), true, Instant.EPOCH, 0, Instant.EPOCH);
                    followerService.insertNewFollower(follower);
                });
            }

            //?????????????? ?? ????
            String[] words = event.getMessage().split(" ");
            if (databaseCommandsService.isCommandExists(words[0])) {
                Command command = databaseCommandsService.getCommandByName(words[0]);
                if (event.getPermissions().contains(command.getPermissionLevel())) {
                    sendMessageToChannelChat(command.getCommandAnswer(), CommandPermission.EVERYONE);
                } else {
                    System.out.println("No access to command");
                }

                return;
            }
            if (event.getMessage().startsWith("!??????????")) {
                channelFollowers.updateFollowersDB();
                return;
            }
            if (event.getMessage().startsWith("SoCute")) {
                sendMessageToChannelChat("SoCute SoCute SoCute SoCute SoCute SoCute", CommandPermission.EVERYONE);
                return;
            }

            if (event.getMessage().startsWith(DatabaseCommandsHandler.ADD_NEW_COMMAND)) {
                if (event.getPermissions().contains(CommandPermission.MODERATOR)) {
                    sendMessageToChannelChat(dataBaseCommandsHandler.addNewCommand(event.getMessage()), CommandPermission.EVERYONE);
                }
                return;
            }

            if (event.getMessage().startsWith(DatabaseCommandsHandler.DELETE_COMMAND)) {
                if (event.getPermissions().contains(CommandPermission.MODERATOR)) {
                    if (dataBaseCommandsHandler.deleteCommand(event.getMessage())) {
                        twitchClient.getChat().sendMessage(twitchClientConfig.getChannelName(), "?????????????? ??????????????!");
                    } else {
                        twitchClient.getChat().sendMessage(twitchClientConfig.getChannelName(), "????????????! ?????????????? ???? ???????? ??????????????");
                    }
                }
                return;
            }

            if (event.getMessage().startsWith("!iq") || event.getMessage().startsWith("!??????????")) {
                handleIqMessage(event);
                return;
            }

            if (event.getMessage().startsWith("!??????????")) {
                handleKarmaMessage(event.getMessage(), event.getUser());
                return;
            }

            if (event.getMessage().startsWith("!??????????")) {
                int size = (int) Math.floor(Math.random() * (6 + 1));

                if (size == 0) {
                    size = 1;
                }
                sendMessageToChannelChat("???????????? ????????????! ????????????????: " + size, CommandPermission.EVERYONE);
                return;
            }

            if (event.getMessage().startsWith("!??????????")) {
                handleDuelMessageStat(event);
                return;
            }
            if (event.getMessage().startsWith("!??????????")) {
                handleDuelMessage(event);
                return;
            }
        });
    }

    void handleKarmaMessage(String message, EventUser user) {

        List<String> strings = List.of(message.split(" "));
        Follower follower = followerService.findById(Long.valueOf(user.getId()));
        if (strings.size() == 1) {
            sendMessageToChannelChat("@" + user.getName() + ", ???????? ?????????? ????????????: " + follower.getKarma(), CommandPermission.EVERYONE);
            return;
        } else if (strings.size() != 3) {
            return;
        }

        if (follower.getChangedSomeonesKarmaLastTime().plusSeconds(1800).isAfter(Instant.now())) {
            sendMessageToChannelChat("?????????? ?????????? ???????????? ???????????? ?????? ?? ??????????????!", CommandPermission.EVERYONE);
            return;
        }
        if (follower.getDisplayName().equalsIgnoreCase(strings.get(1).replaceAll("@", ""))) {
            sendMessageToChannelChat("???? ??????, ?????????????? ???????????????? ???????? ?????????????  Susge", CommandPermission.EVERYONE);
            return;
        }
        Follower followerToChangeKarma = followerService.findByDisplayName(strings.get(1).replaceAll("@", ""));
        if (followerToChangeKarma == null) {
            sendMessageToChannelChat("?????????? ?????????? ?? ???????????????????? ?? ?????? ??????!", CommandPermission.EVERYONE);
            return;
        }

        follower.setChangedSomeonesKarmaLastTime(Instant.now());
        followerService.saveFollower(follower);
        if (strings.get(2).contains("+")) {
            followerToChangeKarma.setKarma(followerToChangeKarma.getKarma() + 1);
        } else if (strings.get(2).contains("-")) {
            followerToChangeKarma.setKarma(followerToChangeKarma.getKarma() - 1);
        }
        followerService.saveFollower(followerToChangeKarma);
        sendMessageToChannelChat("?????????? " + followerToChangeKarma.getDisplayName() + " ????????????????!", CommandPermission.EVERYONE);

    }

    void handleDuelMessageStat(ChannelMessageEvent event) {
        DuelistStats duelist = duelistStatService.getAllByUserId(Long.valueOf(event.getUser().getId()));
        sendMessageToChannelChat("@" + duelist.getFollower().getDisplayName() + " ?????????? ?? ??????????: " +
                +duelist.getWins() + ", ????????????????????: " + duelist.getLoses() + ", ?????????? ????????????: "
                +duelist.getWinstreak()+", ???????????? ?????????? ??????????: " + duelist.getWinstreakMax(), CommandPermission.EVERYONE);
    }

    void handleDuelMessage(ChannelMessageEvent event) {
        DuelistStats duelist = duelistStatService.getAllByUserId(Long.valueOf(event.getUser().getId()));
        if (firstDuelistTriesToFindSomeoneTime.plusSeconds(1800).isBefore(Instant.now())) {
            firstDuelistTriesToFindSomeoneTime = Instant.now();
            duelistOne = null;
            System.out.println("creating new duel");
        }
        if (duelistOne == null) {
            duelistOne = duelist;
            sendMessageToChatIgnoreTimer("@" + duelistOne.getFollower().getDisplayName() + " ?????????? ?????????????????????? ?? ??????????! ???????? ?????????????? ????????????????!");
            firstDuelistTriesToFindSomeoneTime = Instant.now();
            return;
        } else {
            duelistTwo = duelist;
            sendMessageToChatIgnoreTimer("@" + duelistTwo.getFollower().getDisplayName() + " ???????????????? ???? ?????????? @"
                    + duelistOne.getFollower().getDisplayName() + "! ???????????????? ??????????!");

            try {
                sendMessageToChatIgnoreTimer("3...");
                Thread.sleep(1000);
                sendMessageToChatIgnoreTimer("2...");
                Thread.sleep(1000);
                sendMessageToChatIgnoreTimer("1...");
                Thread.sleep(1000);
                sendMessageToChatIgnoreTimer("??????!");
                double result = Math.random();
                if (result < 0.5) {
                    sendMessageToChatIgnoreTimer("@" + duelistOne.getFollower().getDisplayName() + " ???????????????????? ??????????! " +
                            "@" + duelistTwo.getFollower().getDisplayName() + " ???????????? ????????????????  NOOOO ");
                    sendAdminMessage("/timeout " + duelistTwo.getFollower().getDisplayName() + " 60 ???????????????? ?? ??????????");
                    duelistOne.setWins(duelistOne.getWins() + 1);
                    duelistTwo.setLoses(duelistTwo.getLoses() + 1);
                    duelistOne.setWinstreak(duelistOne.getWinstreak()+1);
                    if(duelistOne.getWinstreakMax()<duelistOne.getWinstreak()){
                        duelistOne.setWinstreakMax(duelistOne.getWinstreak());
                    }

                    duelistTwo.setWinstreak(0);
                } else {
                    sendMessageToChatIgnoreTimer("@" + duelistTwo.getFollower().getDisplayName() + " ???????????? ?????????????? ????????????! " +
                            "@" + duelistOne.getFollower().getDisplayName() + " ???????????? ????????????????  DIESOFCRINGE ");
                    sendAdminMessage("/timeout " + duelistOne.getFollower().getDisplayName() + " 60 ???????????????? ?? ??????????");
                    duelistTwo.setWins(duelistTwo.getWins() + 1);
                    duelistOne.setLoses(duelistOne.getLoses() + 1);
                    duelistTwo.setWinstreak(duelistTwo.getWinstreak()+1);
                    if(duelistTwo.getWinstreakMax()<duelistTwo.getWinstreak()){
                        duelistTwo.setWinstreakMax(duelistTwo.getWinstreak());
                    }

                    duelistOne.setWinstreak(0);
                }
                firstDuelistTriesToFindSomeoneTime = Instant.EPOCH;
                duelistStatService.saveDuelistStats(duelistOne);
                duelistStatService.saveDuelistStats(duelistTwo);
                duelistOne = null;
                duelistTwo = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
                sendMessageToChatIgnoreTimer("???????????????????? ?????????? ????????????????, ???????????????? ???????????????? ????????!");
            }
        }
    }

    void handleIqMessage(ChannelMessageEvent event) {
        Iq iq = iqService.findByName(event.getUser().getName());

        if (iq == null) {
            iq = iqService.insertCucumber(new Iq(null, event.getUser().getName(),
                    Instant.now(), (int) Math.floor(MIN_IQ + (int) (Math.random() * (MAX_IQ)))));
            sendMessageToChannelChat("@" + event.getUser().getName() + " ??????! ???????? ?????????? ?????????????? " + iq.getSize() + "! ?? ???????????? ??????????????! PepegaAim", CommandPermission.EVERYONE);
            return;
        } else {
            if (iq.getTime().plusSeconds(86400).isAfter(Instant.now())) {
                sendMessageToChannelChat("@" + event.getUser().getName() + " ???????????????????? ?????????? ?????????? ?????? ??????! NOPERS ?????????????????? ?????????????????? " + iq.getSize() + "!", CommandPermission.EVERYONE);
                return;
            } else {
                int size = iq.getSize();
                iq.setSize((int) Math.floor(Math.random() * (MAX_IQ - MIN_IQ + 1) + MIN_IQ));
                iq = iqService.updateCucumber(iq);

                if (iq.getSize() == MAX_IQ) {
                    sendMessageToChannelChat("@" + event.getUser().getName() + " ???????????? ????????! ?????????????????? ???????????????????? " + iq.getSize() + "! ???????????? ?????????? ????????????! peepoClap", CommandPermission.EVERYONE);
                    return;
                }

                if (size < iq.getSize()) {
                    sendMessageToChannelChat("@" + event.getUser().getName() + " ??????! ?????????????????? ???????????????????? " + iq.getSize() + "! ???? ???????? ??????????! widepeepoHappy ", CommandPermission.EVERYONE);
                } else if (size > iq.getSize()) {
                    sendMessageToChannelChat("@" + event.getUser().getName() + " ????????. ???? ??????????????????????. ?????????????????? ???????????????????? " + iq.getSize() + "!  ?? ???????? " + size + " widepeepoSad", CommandPermission.EVERYONE);
                } else {
                    sendMessageToChannelChat("@" + event.getUser().getName() + " ?????????????????? ???????????????????? " + iq.getSize() + ". ???????????? ???? ???????????????????? !  peepoGiggles", CommandPermission.EVERYONE);
                }
            }
        }

    }

    public void sendAdminMessage(String message) {
        twitchClient.getChat().sendMessage(twitchClientConfig.getChannelName(), message);
    }

    public void sendMessageToChannelChat(String message, CommandPermission messageSentBy) {
        if (message.startsWith("/")) {
            return;
        }
        if (lastMessageTime.plusSeconds(MESSAGE_TIMEOUT_EVERYONE).isAfter(Instant.now())) {
            if (messageSentBy.ordinal() < CommandPermission.VIP.ordinal()) {
                return;
            }
        }
        lastMessageTime = Instant.now();
        twitchClient.getChat().sendMessage(twitchClientConfig.getChannelName(), message);
    }

    public void sendMessageToChatIgnoreTimer(String message) {
        if (message.startsWith("/")) {
            return;
        }
        twitchClient.getChat().sendMessage(twitchClientConfig.getChannelName(), message);
    }
}
