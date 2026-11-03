package io.github.sx2xoox.mcpl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;
import io.github.sx2xoox.mcpl.MCPLPlugin;
import io.github.sx2xoox.mcpl.core.command.GameCommand;
import io.github.sx2xoox.mcpl.core.command.MovedCommands;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.game.augment.AugmentPracticeGame;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerPracticeGame;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The command layout: every start under {@code /게임 시작}, the common in-match commands, {@code /게임 연습 <도구>},
 * {@code /게임 맵}, the old forms still routing (hidden), tab completion of only the new forms, and the help.
 */
class CommandLayoutTest {

    private ServerMock server;
    private Hub hub;
    private PlayerMock admin;
    private PlayerMock user;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        hub = MockBukkit.load(MCPLPlugin.class).hub();
        admin = server.addPlayer("Admin");
        admin.setOp(true);
        user = server.addPlayer("User");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private record Source(CommandSender sender, Location location) implements CommandSourceStack {

        @Override
        public Location getLocation() {
            return location;
        }

        @Override
        public CommandSender getSender() {
            return sender;
        }

        @Override
        public @Nullable Entity getExecutor() {
            return sender instanceof Entity entity ? entity : null;
        }

        @Override
        public Player getPlayerOrThrow() {
            return (Player) sender;
        }

        @Override
        public Entity getEntityOrThrow() {
            return (Entity) sender;
        }

        @Override
        public CommandSourceStack withLocation(Location location) {
            return new Source(sender, location);
        }

        @Override
        public CommandSourceStack withExecutor(Entity executor) {
            return this;
        }
    }

    /**
     * What tab completion offers after {@code typed}: Brigadier's completion over the built tree, without the
     * subcommands the player may not use (the server never sends those to the client).
     */
    private List<String> complete(PlayerMock player, String typed) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new GameCommand(hub).build());
        Source source = new Source(player, player.getLocation());
        ParseResults<CommandSourceStack> parse = dispatcher.parse(typed, source);
        CommandNode<CommandSourceStack> parent = parse.getContext().findSuggestionContext(typed.length()).parent;
        List<String> out = new ArrayList<>();
        dispatcher.getCompletionSuggestions(parse).join().getList().forEach(suggestion -> {
            CommandNode<CommandSourceStack> child = parent.getChild(suggestion.getText());
            if (child == null || child.canUse(source)) {
                out.add(suggestion.getText());
            }
        });
        return out;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<Component> drain(PlayerMock player) {
        List<Component> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(next);
        }
        return lines;
    }

    private static String text(PlayerMock player) {
        StringBuilder out = new StringBuilder();
        drain(player).forEach(line -> out.append(plain(line)).append('\n'));
        return out.toString();
    }

    // ------------------------------------------------------------------ routing

    @Test
    void everyStartGoesThroughStart() {
        drain(admin);
        assertTrue(server.dispatchCommand(admin, "게임 시작 초능력전쟁 연습"));
        GameSession session = hub.games().session();
        assertNotNull(session);
        assertEquals(SuperpowerPracticeGame.ID, session.game().id());
        server.dispatchCommand(admin, "게임 중지");
        assertNull(hub.games().session());

        server.dispatchCommand(admin, "mcpl start augment practice");
        assertNotNull(hub.games().session());
        assertEquals(AugmentPracticeGame.ID, hub.games().session().game().id());
        server.dispatchCommand(admin, "게임 중지");

        server.dispatchCommand(admin, "게임 시작 초능력전쟁 연습 모두");
        assertEquals(SuperpowerPracticeGame.ID, hub.games().session().game().id());
        server.dispatchCommand(admin, "게임 중지");
    }

    @Test
    void playersCannotStartMatches() {
        server.dispatchCommand(user, "게임 시작 초능력전쟁");
        assertNull(hub.games().session());
        server.dispatchCommand(user, "게임 시작 초능력전쟁 연습");
        assertNull(hub.games().session(), "only admins start a practice; everyone can join one");
        server.dispatchCommand(user, "게임 시작 증강 봇전 3 쉬움");
        assertNull(hub.games().session());
    }

    @Test
    void oldFormsStillWorkWithAHint() {
        drain(admin);
        assertTrue(server.dispatchCommand(admin, "게임 능력 연습"));
        assertNotNull(hub.games().session());
        assertEquals(SuperpowerPracticeGame.ID, hub.games().session().game().id());
        assertTrue(text(admin).contains("/게임 시작 초능력전쟁 연습"), "the hint names the new form");
        server.dispatchCommand(admin, "게임 중지");

        server.dispatchCommand(admin, "게임 시작 연습");
        assertEquals(SuperpowerPracticeGame.ID, hub.games().session().game().id(), "/게임 시작 연습 = 모두 연습");
        server.dispatchCommand(admin, "게임 중지");

        server.dispatchCommand(admin, "mcpl augment practice");
        assertEquals(AugmentPracticeGame.ID, hub.games().session().game().id());
        server.dispatchCommand(admin, "게임 중지");

        drain(admin);
        server.dispatchCommand(admin, "게임 경기장 설정 40");
        assertTrue(hub.config().hasArena());
        assertEquals(40, hub.config().arenaRadius());
        assertTrue(text(admin).contains("/게임 맵 여기"));
        server.dispatchCommand(admin, "게임 경기장 초기화");
        assertFalse(hub.config().hasArena());
    }

    @Test
    void movedFormsRewriteToTheNewWords() {
        MovedCommands moved = new MovedCommands();
        moved.add("증강|augment 봇전|bots", "시작 증강 봇전");
        moved.add("증강|augment 봇전|bots 관전|spectate", "시작 증강 관전");
        assertEquals(List.of("시작", "증강", "관전", "8", "지옥"),
                moved.rewrite(MovedCommands.split("augment bots spectate 8 지옥")));
        assertEquals(List.of("시작", "증강", "봇전", "팀", "3", "4"), moved.rewrite(MovedCommands.split("증강 봇전 팀 3 4")));
        assertNull(moved.rewrite(List.of("증강", "목록")));
        moved.add("능력|ability 쿨타임|cooldown", "연습 쿨타임");
        assertEquals(List.of("연습", "쿨타임", "켜기"), moved.rewrite(MovedCommands.split("ability cooldown on")));
    }

    @Test
    void mapCommandsSetAndClearTheArena() {
        server.dispatchCommand(admin, "게임 맵 여기 50");
        assertTrue(hub.config().hasArena());
        assertEquals(50, hub.config().arenaRadius());
        assertEquals(admin.getLocation().getBlockX(), hub.config().arenaCenter().getBlockX());
        server.dispatchCommand(admin, "mcpl map clear");
        assertFalse(hub.config().hasArena());
        server.dispatchCommand(user, "게임 맵 여기 50");
        assertFalse(hub.config().hasArena(), "maps are for admins");
    }

    @Test
    void commonCommandsAnswerWithoutAMatch() {
        drain(user);
        server.dispatchCommand(user, "게임 리롤");
        server.dispatchCommand(user, "게임 점수");
        server.dispatchCommand(user, "게임 내정보");
        server.dispatchCommand(user, "게임 팀");
        String said = text(user);
        assertEquals(4, said.split("진행 중인 경기가 없습니다", -1).length - 1, said);

        server.dispatchCommand(user, "게임 연습 허수아비");
        assertTrue(text(user).contains("진행 중인 연습이 없습니다"));
    }

    @Test
    void practiceToolsOfAnotherPracticeSayWhichPracticeTheyAreFor() {
        server.dispatchCommand(admin, "게임 시작 증강 연습");
        assertEquals(AugmentPracticeGame.ID, hub.games().session().game().id());
        drain(admin);
        server.dispatchCommand(admin, "게임 연습 충전");
        assertTrue(text(admin).contains("능력 연습에서만"));
        server.dispatchCommand(admin, "게임 점수");
        assertTrue(text(admin).contains("점수가 없습니다"));
        server.dispatchCommand(admin, "게임 중지");
    }

    @Test
    void unknownWordsAreReported() {
        drain(user);
        server.dispatchCommand(user, "게임 아무거나 해줘");
        assertTrue(text(user).contains("알 수 없는 명령어"));
    }

    // ------------------------------------------------------------------ tab completion

    @Test
    void tabCompletionListsOnlyTheNewForms() {
        List<String> root = complete(admin, "게임 ");
        for (String word : List.of("시작", "리롤", "점수", "내정보", "팀", "연습", "맵", "능력", "증강", "공성전", "설정")) {
            assertTrue(root.contains(word), word + " in " + root);
        }
        assertFalse(root.contains("경기장"), root.toString());

        List<String> ability = complete(admin, "게임 능력 ");
        assertTrue(ability.containsAll(List.of("목록", "정보", "지정", "선택방식")), ability.toString());
        for (String old : List.of("리롤", "팀", "연습", "변경", "충전", "쿨타임", "허수아비")) {
            assertFalse(ability.contains(old), old + " in " + ability);
        }
        List<String> augment = complete(admin, "게임 증강 ");
        assertTrue(augment.containsAll(List.of("목록", "정보", "보너스", "배속", "관전")), augment.toString());
        for (String old : List.of("리롤", "연습", "봇전", "받기", "빼기", "카드", "다음단계", "허수아비", "대전")) {
            assertFalse(augment.contains(old), old + " in " + augment);
        }
        assertEquals(List.of("능력풀", "봇"), complete(admin, "게임 공성전 ").stream()
                .filter(w -> !w.equals("pool") && !w.equals("bots")).toList());

        List<String> start = complete(admin, "게임 시작 ");
        assertTrue(start.containsAll(List.of("초능력전쟁", "증강", "공성전")), start.toString());
        assertFalse(start.contains("연습"), start.toString());
        assertTrue(complete(admin, "게임 시작 증강 ").containsAll(List.of("연습", "봇전", "관전")));
        assertTrue(complete(admin, "게임 시작 증강 봇전 ").contains("팀"));
        assertTrue(complete(admin, "게임 시작 공성전 ").contains("봇전"));
        assertTrue(complete(admin, "게임 시작 공성전 봇전 ").containsAll(List.of("3", "쉬움", "보통", "어려움")));
        assertTrue(complete(admin, "게임 시작 초능력전쟁 연습 ").contains("모두"));
        assertTrue(complete(admin, "게임 연습 ").containsAll(List.of("허수아비", "바꾸기", "충전", "쿨타임", "받기",
                "빼기", "카드", "다음단계", "대전")));
        assertTrue(complete(admin, "게임 맵 ").containsAll(List.of("여기", "해제")));

        List<String> forUser = complete(user, "게임 ");
        assertFalse(forUser.contains("맵"), forUser.toString());
        assertFalse(forUser.contains("중지"), forUser.toString());
        assertFalse(complete(user, "게임 시작 ").contains("공성전"));
        assertFalse(complete(user, "게임 연습 ").contains("쿨타임"));
    }

    // ------------------------------------------------------------------ help

    @Test
    void helpIsCategorisedAndFilteredByPermission() {
        drain(admin);
        server.dispatchCommand(admin, "게임");
        List<Component> lines = drain(admin);
        String adminHelp = String.join("\n", lines.stream().map(CommandLayoutTest::plain).toList());
        for (String title : List.of("시작", "진행", "경기 중", "연습", "모드별", "맵", "서버")) {
            assertTrue(adminHelp.contains("▸ " + title), title + "\n" + adminHelp);
        }
        assertTrue(adminHelp.contains("/게임 시작 증강 봇전 [수] [난이도]"), adminHelp);
        assertTrue(adminHelp.contains("/게임 시작 공성전 봇전 [팀당 인원] [난이도]"), adminHelp);
        assertFalse(adminHelp.contains("<cmd"), "every command tag rendered:\n" + adminHelp);
        assertFalse(adminHelp.contains("missing"), adminHelp);
        for (String old : List.of("/게임 경기장", "/게임 능력 연습", "/게임 증강 연습", "/게임 증강 봇전", "/게임 능력 리롤")) {
            assertFalse(adminHelp.contains(old), old + "\n" + adminHelp);
        }
        assertTrue(lines.stream().anyMatch(CommandLayoutTest::suggestsACommand), "the commands are clickable");

        drain(user);
        server.dispatchCommand(user, "게임 도움말");
        String userHelp = text(user);
        assertTrue(userHelp.contains("▸ 경기 중") && userHelp.contains("/게임 리롤"), userHelp);
        assertTrue(userHelp.contains("/게임 시작 초능력전쟁 연습"), "players join a practice:\n" + userHelp);
        assertFalse(userHelp.contains("▸ 시작"), userHelp);
        assertFalse(userHelp.contains("▸ 맵"), userHelp);
        assertFalse(userHelp.contains("/게임 중지"), userHelp);
    }

    private static boolean suggestsACommand(Component component) {
        ClickEvent<?> click = component.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.SUGGEST_COMMAND) {
            return true;
        }
        return component.children().stream().anyMatch(CommandLayoutTest::suggestsACommand);
    }
}
