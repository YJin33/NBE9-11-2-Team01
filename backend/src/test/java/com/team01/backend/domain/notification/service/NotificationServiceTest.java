package com.team01.backend.domain.notification.service;

import com.team01.backend.domain.comment.dto.CommentRequestDto;
import com.team01.backend.domain.comment.dto.CommentResponseDto;
import com.team01.backend.domain.comment.repository.CommentRepository;
import com.team01.backend.domain.comment.service.CommentService;
import com.team01.backend.domain.notification.entity.Notification;
import com.team01.backend.domain.notification.repository.NotificationRepository;
import com.team01.backend.domain.notification.repository.SseEmitterRepository;
import com.team01.backend.domain.post.entity.Post;
import com.team01.backend.domain.post.service.PostService;
import com.team01.backend.domain.user.entity.Role;
import com.team01.backend.domain.user.entity.User;
import com.team01.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
@SpringBootTest
@ActiveProfiles("test")
class NotificationServiceTest {

    private static final long ASYNC_TIMEOUT_MS = 5_000;
    private static final Long BOARD_ID = 1L;
    private static final Long CATEGORY_ID = 2L;

    @Autowired
    private CommentService commentService;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PostService postService;

    @MockitoBean
    private SseEmitterRepository emitterRepository;

    private User newUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(
                new User(prefix + "-" + suffix + "@test.com", "pw", prefix + "-" + suffix, null, Role.USER)
        );
    }

    @Test
    void notifyPostOwnerOnComment() throws Exception {
        User postOwner = newUser("owner");
        User commenter = newUser("commenter");
        Post post = postService.write(postOwner.getEmail(), "title", "content", BOARD_ID, CATEGORY_ID);

        SseEmitter emitter = mock(SseEmitter.class);
        AtomicReference<Thread> listenerThread = new AtomicReference<>();
        when(emitterRepository.findByUserId(postOwner.getId())).thenAnswer(inv -> {
            listenerThread.set(Thread.currentThread());
            return List.of(emitter);
        });

        commentService.writeComment(post.getId(), new CommentRequestDto("comment1", null), commenter.getEmail());

        verify(emitter, timeout(ASYNC_TIMEOUT_MS)).send(isA(Notification.class));

        assertThat(listenerThread.get()).isNotNull().isNotSameAs(Thread.currentThread());

        List<Notification> notifications =
                notificationRepository.findByReceiverIdOrderByCreatedAtDesc(postOwner.getId());
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getSenderId()).isEqualTo(commenter.getId());
        assertThat(notifications.get(0).getTargetId()).isEqualTo(post.getId());
        assertThat(notifications.get(0).getContent()).startsWith("댓글이 달렸습니다.");
    }

    @Test
    void notifyCommentWriterOnReply() throws Exception {
        User postOwner = newUser("owner");
        User commenter = newUser("commenter");
        User replier = newUser("replier");
        Post post = postService.write(postOwner.getEmail(), "title", "content", BOARD_ID, CATEGORY_ID);

        CommentResponseDto parent = commentService.writeComment(
                post.getId(), new CommentRequestDto("parent", null), commenter.getEmail());

        SseEmitter emitter = mock(SseEmitter.class);
        AtomicReference<Thread> listenerThread = new AtomicReference<>();
        when(emitterRepository.findByUserId(commenter.getId())).thenAnswer(inv -> {
            listenerThread.set(Thread.currentThread());
            return List.of(emitter);
        });

        commentService.writeComment(post.getId(), new CommentRequestDto("reply1", parent.id()), replier.getEmail());

        verify(emitter, timeout(ASYNC_TIMEOUT_MS)).send(isA(Notification.class));
        assertThat(listenerThread.get()).isNotNull().isNotSameAs(Thread.currentThread());

        List<Notification> notifications =
                notificationRepository.findByReceiverIdOrderByCreatedAtDesc(commenter.getId());
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getSenderId()).isEqualTo(replier.getId());
        assertThat(notifications.get(0).getContent()).startsWith("답글이 달렸습니다.");
    }

    @Test
    void commentSavedWhenNotificationFails() throws Exception {
        User postOwner = newUser("owner");
        User commenter = newUser("commenter");
        Post post = postService.write(postOwner.getEmail(), "title", "content", BOARD_ID, CATEGORY_ID);

        AtomicReference<Boolean> listenerCalled = new AtomicReference<>(false);
        when(emitterRepository.findByUserId(postOwner.getId())).thenAnswer(inv -> {
            listenerCalled.set(true);
            throw new IllegalStateException("sse failure");
        });

        CommentResponseDto saved = commentService.writeComment(
                post.getId(), new CommentRequestDto("comment-survives", null), commenter.getEmail());

        verify(emitterRepository, timeout(ASYNC_TIMEOUT_MS)).findByUserId(postOwner.getId());
        assertThat(listenerCalled.get()).isTrue();

        assertThat(commentRepository.findById(saved.id())).isPresent();
    }
}
