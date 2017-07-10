/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.esnative;

import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.env.Environment;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.XPackSettings;
import org.elasticsearch.xpack.security.SecurityLifecycleService;
import org.elasticsearch.xpack.security.action.user.ChangePasswordRequest;
import org.elasticsearch.xpack.security.authc.IncomingRequest;
import org.elasticsearch.xpack.security.authc.esnative.NativeUsersStore.ReservedUserInfo;
import org.elasticsearch.xpack.security.authc.support.Hasher;
import org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken;
import org.elasticsearch.xpack.security.user.AnonymousUser;
import org.elasticsearch.xpack.security.user.BeatsSystemUser;
import org.elasticsearch.xpack.security.user.ElasticUser;
import org.elasticsearch.xpack.security.user.KibanaUser;
import org.elasticsearch.xpack.security.user.LogstashSystemUser;
import org.elasticsearch.xpack.security.user.User;
import org.junit.Before;
import org.mockito.ArgumentCaptor;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link ReservedRealm}
 */
public class ReservedRealmTests extends ESTestCase {

    private static final SecureString EMPTY_PASSWORD = new SecureString("".toCharArray());
    public static final String ACCEPT_DEFAULT_PASSWORDS = ReservedRealm.ACCEPT_DEFAULT_PASSWORD_SETTING.getKey();
    private NativeUsersStore usersStore;
    private SecurityLifecycleService securityLifecycleService;
    private IncomingRequest incomingRequest;

    @Before
    public void setupMocks() throws Exception {
        usersStore = mock(NativeUsersStore.class);
        securityLifecycleService = mock(SecurityLifecycleService.class);
        incomingRequest = mock(IncomingRequest.class);
        when(securityLifecycleService.isSecurityIndexAvailable()).thenReturn(true);
        when(securityLifecycleService.checkSecurityMappingVersion(any())).thenReturn(true);
        mockGetAllReservedUserInfo(usersStore, Collections.emptyMap());
    }

    @SuppressForbidden(reason = "allow getting localhost")
    public void testMappingVersionFromBeforeUserExisted() throws ExecutionException, InterruptedException, UnknownHostException {
        when(securityLifecycleService.checkSecurityMappingVersion(any())).thenReturn(false);
        final ReservedRealm reservedRealm =
            new ReservedRealm(mock(Environment.class), Settings.EMPTY, usersStore,
                              new AnonymousUser(Settings.EMPTY), securityLifecycleService, new ThreadContext(Settings.EMPTY));
        final String principal = ElasticUser.NAME;

        PlainActionFuture<User> future = new PlainActionFuture<>();
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLocalHost(), 100);
        when(incomingRequest.getRemoteAddress()).thenReturn(address);
        when(incomingRequest.getType()).thenReturn(IncomingRequest.RequestType.REST);
        reservedRealm.authenticate(new UsernamePasswordToken(principal, EMPTY_PASSWORD), future, incomingRequest);
        assertThat(future.get().enabled(), equalTo(false));
    }

    public void testDisableDefaultPasswordAuthentication() throws Throwable {
        final User expected = randomFrom(new ElasticUser(true), new KibanaUser(true), new LogstashSystemUser(true));

        final Environment environment = mock(Environment.class);
        final AnonymousUser anonymousUser = new AnonymousUser(Settings.EMPTY);
        final Settings settings = Settings.builder().put(ACCEPT_DEFAULT_PASSWORDS, false).build();
        final ReservedRealm reservedRealm = new ReservedRealm(environment, settings, usersStore, anonymousUser,
                securityLifecycleService, new ThreadContext(Settings.EMPTY));

        final ActionListener<User> listener = new ActionListener<User>() {
            @Override
            public void onResponse(User user) {
                fail("Authentication should have failed because default-password is not allowed");
            }

            @Override
            public void onFailure(Exception e) {
                assertThat(e, instanceOf(ElasticsearchSecurityException.class));
                assertThat(e.getMessage(), containsString("failed to authenticate"));
            }
        };
        reservedRealm.doAuthenticate(new UsernamePasswordToken(expected.principal(), EMPTY_PASSWORD), listener, incomingRequest);
    }

    public void testElasticEmptyPasswordAuthenticationFailsFromNonLocalhost() throws Throwable {
        final User expected = new ElasticUser(true);
        final String principal = expected.principal();

        Settings settings = Settings.builder().put(ACCEPT_DEFAULT_PASSWORDS, true).build();
        final ReservedRealm reservedRealm =
                new ReservedRealm(mock(Environment.class), settings, usersStore,
                        new AnonymousUser(Settings.EMPTY), securityLifecycleService, new ThreadContext(Settings.EMPTY));

        PlainActionFuture<User> listener = new PlainActionFuture<>();

        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("128.9.8.1"), 100);

        when(incomingRequest.getRemoteAddress()).thenReturn(address);
        reservedRealm.doAuthenticate(new UsernamePasswordToken(principal, EMPTY_PASSWORD),  listener, incomingRequest);

        ElasticsearchSecurityException actual = expectThrows(ElasticsearchSecurityException.class, listener::actionGet);
        assertThat(actual.getMessage(), containsString("failed to authenticate user [" + principal));
    }

    @SuppressForbidden(reason = "allow getting localhost")
    public void testElasticEmptyPasswordAuthenticationSucceedsInSetupModeIfRestRequestComesFromLocalhost() throws Throwable {
        final User expected = new ElasticUser(true, true);
        final String principal = expected.principal();

        Settings settings = Settings.builder().put(ACCEPT_DEFAULT_PASSWORDS, true).build();
        final ReservedRealm reservedRealm =
                new ReservedRealm(mock(Environment.class), settings, usersStore,
                        new AnonymousUser(Settings.EMPTY), securityLifecycleService, new ThreadContext(Settings.EMPTY));

        PlainActionFuture<User> listener = new PlainActionFuture<>();

        InetSocketAddress address = new InetSocketAddress(InetAddress.getLocalHost(), 100);

        when(incomingRequest.getRemoteAddress()).thenReturn(address);
        when(incomingRequest.getType()).thenReturn(IncomingRequest.RequestType.REST);
        reservedRealm.doAuthenticate(new UsernamePasswordToken(principal, EMPTY_PASSWORD),  listener, incomingRequest);

        User user = listener.actionGet();

        assertEquals(expected, user);
        assertNotEquals(new ElasticUser(true, false), user);
    }

    public void testAuthenticationDisabled() throws Throwable {
        Settings settings = Settings.builder().put(XPackSettings.RESERVED_REALM_ENABLED_SETTING.getKey(), false).build();
        final boolean securityIndexExists = randomBoolean();
        if (securityIndexExists) {
            when(securityLifecycleService.isSecurityIndexExisting()).thenReturn(true);
        }
        final ReservedRealm reservedRealm =
            new ReservedRealm(mock(Environment.class), settings, usersStore,
                              new AnonymousUser(settings), securityLifecycleService, new ThreadContext(Settings.EMPTY));
        final User expected = randomFrom(new ElasticUser(true), new KibanaUser(true), new LogstashSystemUser(true));
        final String principal = expected.principal();

        PlainActionFuture<User> listener = new PlainActionFuture<>();
        reservedRealm.doAuthenticate(new UsernamePasswordToken(principal, EMPTY_PASSWORD), listener, mock(IncomingRequest.class));
        final User authenticated = listener.actionGet();
        assertNull(authenticated);
        verifyZeroInteractions(usersStore);
    }

    public void testAuthenticationEnabledUserWithStoredPassword() throws Throwable {
        verifySuccessfulAuthentication(true);
    }

    public void testAuthenticationDisabledUserWithStoredPassword() throws Throwable {
        verifySuccessfulAuthentication(false);
    }

    private void verifySuccessfulAuthentication(boolean enabled) {
        final Settings settings = Settings.builder().put(ACCEPT_DEFAULT_PASSWORDS, randomBoolean()).build();
        final ReservedRealm reservedRealm = new ReservedRealm(mock(Environment.class), settings, usersStore,
                new AnonymousUser(settings), securityLifecycleService, new ThreadContext(Settings.EMPTY));
        final User expectedUser = randomFrom(new ElasticUser(enabled), new KibanaUser(enabled), new LogstashSystemUser(enabled));
        final String principal = expectedUser.principal();
        final SecureString newPassword = new SecureString("foobar".toCharArray());
        when(securityLifecycleService.isSecurityIndexExisting()).thenReturn(true);
        doAnswer((i) -> {
            ActionListener callback = (ActionListener) i.getArguments()[1];
            callback.onResponse(new ReservedUserInfo(Hasher.BCRYPT.hash(newPassword), enabled, false));
            return null;
        }).when(usersStore).getReservedUserInfo(eq(principal), any(ActionListener.class));

        // test empty password
        final PlainActionFuture<User> listener = new PlainActionFuture<>();
        reservedRealm.doAuthenticate(new UsernamePasswordToken(principal, EMPTY_PASSWORD), listener, incomingRequest);
        ElasticsearchSecurityException expected = expectThrows(ElasticsearchSecurityException.class, listener::actionGet);
        assertThat(expected.getMessage(), containsString("failed to authenticate user [" + principal));

        // the realm assumes it owns the hashed password so it fills it with 0's
        doAnswer((i) -> {
            ActionListener callback = (ActionListener) i.getArguments()[1];
            callback.onResponse(new ReservedUserInfo(Hasher.BCRYPT.hash(newPassword), true, false));
            return null;
        }).when(usersStore).getReservedUserInfo(eq(principal), any(ActionListener.class));

        // test new password
        final PlainActionFuture<User> authListener = new PlainActionFuture<>();
        reservedRealm.doAuthenticate(new UsernamePasswordToken(principal, newPassword), authListener, incomingRequest);
        final User authenticated = authListener.actionGet();
        assertEquals(expectedUser, authenticated);
        assertThat(expectedUser.enabled(), is(enabled));

        verify(securityLifecycleService, times(2)).isSecurityIndexExisting();
        verify(usersStore, times(2)).getReservedUserInfo(eq(principal), any(ActionListener.class));
        final ArgumentCaptor<Predicate> predicateCaptor = ArgumentCaptor.forClass(Predicate.class);
        verify(securityLifecycleService, times(2)).checkSecurityMappingVersion(predicateCaptor.capture());
        verifyVersionPredicate(principal, predicateCaptor.getValue());
        verifyNoMoreInteractions(usersStore);
    }

    public void testLookup() throws Exception {
        final ReservedRealm reservedRealm =
            new ReservedRealm(mock(Environment.class), Settings.EMPTY, usersStore,
                              new AnonymousUser(Settings.EMPTY), securityLifecycleService, new ThreadContext(Settings.EMPTY));
        final User expectedUser = randomFrom(new ElasticUser(true, true), new KibanaUser(true), new LogstashSystemUser(true));
        final String principal = expectedUser.principal();

        PlainActionFuture<User> listener = new PlainActionFuture<>();
        reservedRealm.doLookupUser(principal, listener);
        final User user = listener.actionGet();
        assertEquals(expectedUser, user);
        verify(securityLifecycleService).isSecurityIndexExisting();

        final ArgumentCaptor<Predicate> predicateCaptor = ArgumentCaptor.forClass(Predicate.class);
        verify(securityLifecycleService).checkSecurityMappingVersion(predicateCaptor.capture());
        verifyVersionPredicate(principal, predicateCaptor.getValue());

        PlainActionFuture<User> future = new PlainActionFuture<>();
        reservedRealm.doLookupUser("foobar", future);
        final User doesntExist = future.actionGet();
        assertThat(doesntExist, nullValue());
        verifyNoMoreInteractions(usersStore);
    }

    public void testLookupDisabled() throws Exception {
        Settings settings = Settings.builder().put(XPackSettings.RESERVED_REALM_ENABLED_SETTING.getKey(), false).build();
        final ReservedRealm reservedRealm =
            new ReservedRealm(mock(Environment.class), settings, usersStore, new AnonymousUser(settings),
                    securityLifecycleService, new ThreadContext(Settings.EMPTY));
        final User expectedUser = randomFrom(new ElasticUser(true), new KibanaUser(true), new LogstashSystemUser(true));
        final String principal = expectedUser.principal();

        PlainActionFuture<User> listener = new PlainActionFuture<>();
        reservedRealm.doLookupUser(principal, listener);
        final User user = listener.actionGet();
        assertNull(user);
        verifyZeroInteractions(usersStore);
    }

    public void testLookupThrows() throws Exception {
        final ReservedRealm reservedRealm =
            new ReservedRealm(mock(Environment.class), Settings.EMPTY, usersStore,
                              new AnonymousUser(Settings.EMPTY), securityLifecycleService, new ThreadContext(Settings.EMPTY));
        final User expectedUser = randomFrom(new ElasticUser(true), new KibanaUser(true), new LogstashSystemUser(true));
        final String principal = expectedUser.principal();
        when(securityLifecycleService.isSecurityIndexExisting()).thenReturn(true);
        final RuntimeException e = new RuntimeException("store threw");
        doAnswer((i) -> {
            ActionListener callback = (ActionListener) i.getArguments()[1];
            callback.onFailure(e);
            return null;
        }).when(usersStore).getReservedUserInfo(eq(principal), any(ActionListener.class));

        PlainActionFuture<User> future = new PlainActionFuture<>();
        reservedRealm.lookupUser(principal, future);
        ElasticsearchSecurityException securityException = expectThrows(ElasticsearchSecurityException.class, future::actionGet);
        assertThat(securityException.getMessage(), containsString("failed to lookup"));

        verify(securityLifecycleService).isSecurityIndexExisting();
        verify(usersStore).getReservedUserInfo(eq(principal), any(ActionListener.class));

        final ArgumentCaptor<Predicate> predicateCaptor = ArgumentCaptor.forClass(Predicate.class);
        verify(securityLifecycleService).checkSecurityMappingVersion(predicateCaptor.capture());
        verifyVersionPredicate(principal, predicateCaptor.getValue());

        verifyNoMoreInteractions(usersStore);
    }

    public void testIsReserved() {
        final User expectedUser = randomFrom(new ElasticUser(true), new KibanaUser(true), new LogstashSystemUser(true));
        final String principal = expectedUser.principal();
        assertThat(ReservedRealm.isReserved(principal, Settings.EMPTY), is(true));

        final String notExpected = randomFrom("foobar", "", randomAlphaOfLengthBetween(1, 30));
        assertThat(ReservedRealm.isReserved(notExpected, Settings.EMPTY), is(false));
    }

    public void testIsReservedDisabled() {
        Settings settings = Settings.builder().put(XPackSettings.RESERVED_REALM_ENABLED_SETTING.getKey(), false).build();
        final User expectedUser = randomFrom(new ElasticUser(true), new KibanaUser(true), new LogstashSystemUser(true));
        final String principal = expectedUser.principal();
        assertThat(ReservedRealm.isReserved(principal, settings), is(false));

        final String notExpected = randomFrom("foobar", "", randomAlphaOfLengthBetween(1, 30));
        assertThat(ReservedRealm.isReserved(notExpected, settings), is(false));
    }

    public void testGetUsers() {
        final ReservedRealm reservedRealm = new ReservedRealm(mock(Environment.class), Settings.EMPTY, usersStore,
                              new AnonymousUser(Settings.EMPTY), securityLifecycleService, new ThreadContext(Settings.EMPTY));
        PlainActionFuture<Collection<User>> userFuture = new PlainActionFuture<>();
        reservedRealm.users(userFuture);
        assertThat(userFuture.actionGet(), containsInAnyOrder(new ElasticUser(true, true), new KibanaUser(true),
                new LogstashSystemUser(true), new BeatsSystemUser(true)));
    }

    public void testGetUsersDisabled() {
        final boolean anonymousEnabled = randomBoolean();
        Settings settings = Settings.builder()
                .put(XPackSettings.RESERVED_REALM_ENABLED_SETTING.getKey(), false)
                .put(AnonymousUser.ROLES_SETTING.getKey(), anonymousEnabled ? "user" : "")
                .build();
        final AnonymousUser anonymousUser = new AnonymousUser(settings);
        final ReservedRealm reservedRealm = new ReservedRealm(mock(Environment.class), settings, usersStore, anonymousUser,
                    securityLifecycleService, new ThreadContext(Settings.EMPTY));
        PlainActionFuture<Collection<User>> userFuture = new PlainActionFuture<>();
        reservedRealm.users(userFuture);
        if (anonymousEnabled) {
            assertThat(userFuture.actionGet(), contains(anonymousUser));
        } else {
            assertThat(userFuture.actionGet(), empty());
        }
    }

    @SuppressForbidden(reason = "allow getting localhost")
    public void testFailedAuthentication() throws UnknownHostException {
        final ReservedRealm reservedRealm = new ReservedRealm(mock(Environment.class), Settings.EMPTY, usersStore,
                              new AnonymousUser(Settings.EMPTY), securityLifecycleService, new ThreadContext(Settings.EMPTY));
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLocalHost(), 100);

        // maybe cache a successful auth
        if (randomBoolean()) {
            PlainActionFuture<User> future = new PlainActionFuture<>();

            IncomingRequest r = mock(IncomingRequest.class);
            when(r.getRemoteAddress()).thenReturn(address);
            when(r.getType()).thenReturn(IncomingRequest.RequestType.REST);
            reservedRealm.authenticate(new UsernamePasswordToken(ElasticUser.NAME, EMPTY_PASSWORD), future, r);
            User user = future.actionGet();
            assertEquals(new ElasticUser(true, true), user);
        }

        PlainActionFuture<User> future = new PlainActionFuture<>();
        IncomingRequest r = mock(IncomingRequest.class);
        when(r.getRemoteAddress()).thenReturn(address);
        when(r.getType()).thenReturn(IncomingRequest.RequestType.REST);
        reservedRealm.authenticate(new UsernamePasswordToken(ElasticUser.NAME, new SecureString("foobar".toCharArray())), future, r);
        ElasticsearchSecurityException e = expectThrows(ElasticsearchSecurityException.class, future::actionGet);
        assertThat(e.getMessage(), containsString("failed to authenticate"));
    }

    @SuppressWarnings("unchecked")
    public void testBootstrapElasticPassword() {
        ReservedUserInfo user = new ReservedUserInfo(ReservedRealm.EMPTY_PASSWORD_HASH, true, true);
        mockGetAllReservedUserInfo(usersStore, Collections.singletonMap(ElasticUser.NAME, user));
        Settings settings = Settings.builder().build();
        when(securityLifecycleService.isSecurityIndexExisting()).thenReturn(true);

        final ReservedRealm reservedRealm = new ReservedRealm(mock(Environment.class), settings, usersStore,
                new AnonymousUser(Settings.EMPTY), securityLifecycleService, new ThreadContext(Settings.EMPTY));
        PlainActionFuture<Boolean> listenerFuture = new PlainActionFuture<>();
        SecureString passwordHash = new SecureString(randomAlphaOfLength(10).toCharArray());
        reservedRealm.bootstrapElasticUserCredentials(passwordHash, listenerFuture);

        ArgumentCaptor<ChangePasswordRequest> requestCaptor = ArgumentCaptor.forClass(ChangePasswordRequest.class);
        ArgumentCaptor<ActionListener> listenerCaptor = ArgumentCaptor.forClass(ActionListener.class);
        verify(usersStore).changePassword(requestCaptor.capture(), listenerCaptor.capture());
        assertEquals(passwordHash.getChars(), requestCaptor.getValue().passwordHash());

        listenerCaptor.getValue().onResponse(null);

        assertTrue(listenerFuture.actionGet());
    }

    public void testBootstrapElasticPasswordNotSetIfPasswordExists() {
        mockGetAllReservedUserInfo(usersStore, Collections.singletonMap(ElasticUser.NAME, new ReservedUserInfo(new char[7], true, false)));
        when(securityLifecycleService.isSecurityIndexExisting()).thenReturn(true);

        Settings settings = Settings.builder().build();
        final ReservedRealm reservedRealm = new ReservedRealm(mock(Environment.class), settings, usersStore,
                new AnonymousUser(Settings.EMPTY), securityLifecycleService, new ThreadContext(Settings.EMPTY));
        SecureString passwordHash = new SecureString(randomAlphaOfLength(10).toCharArray());
        reservedRealm.bootstrapElasticUserCredentials(passwordHash, new PlainActionFuture<>());

        verify(usersStore, times(0)).changePassword(any(ChangePasswordRequest.class), any());
    }

    public void testBootstrapElasticPasswordSettingFails() {
        ReservedUserInfo user = new ReservedUserInfo(ReservedRealm.EMPTY_PASSWORD_HASH, true, true);
        mockGetAllReservedUserInfo(usersStore, Collections.singletonMap(ElasticUser.NAME, user));
        Settings settings = Settings.builder().build();
        when(securityLifecycleService.isSecurityIndexExisting()).thenReturn(true);

        final ReservedRealm reservedRealm = new ReservedRealm(mock(Environment.class), settings, usersStore,
                new AnonymousUser(Settings.EMPTY), securityLifecycleService, new ThreadContext(Settings.EMPTY));
        PlainActionFuture<Boolean> listenerFuture = new PlainActionFuture<>();
        SecureString passwordHash = new SecureString(randomAlphaOfLength(10).toCharArray());
        reservedRealm.bootstrapElasticUserCredentials(passwordHash, listenerFuture);

        ArgumentCaptor<ChangePasswordRequest> requestCaptor = ArgumentCaptor.forClass(ChangePasswordRequest.class);
        ArgumentCaptor<ActionListener> listenerCaptor = ArgumentCaptor.forClass(ActionListener.class);
        verify(usersStore).changePassword(requestCaptor.capture(), listenerCaptor.capture());
        assertEquals(passwordHash.getChars(), requestCaptor.getValue().passwordHash());

        listenerCaptor.getValue().onFailure(new RuntimeException());

        expectThrows(RuntimeException.class, listenerFuture::actionGet);
    }

    /*
     * NativeUserStore#getAllReservedUserInfo is pkg private we can't mock it otherwise
     */
    public static void mockGetAllReservedUserInfo(NativeUsersStore usersStore, Map<String, ReservedUserInfo> collection) {
        doAnswer((i) -> {
            ((ActionListener) i.getArguments()[0]).onResponse(collection);
            return null;
        }).when(usersStore).getAllReservedUserInfo(any(ActionListener.class));

        for (Entry<String, ReservedUserInfo> entry : collection.entrySet()) {
            doAnswer((i) -> {
                ((ActionListener) i.getArguments()[1]).onResponse(entry.getValue());
                return null;
            }).when(usersStore).getReservedUserInfo(eq(entry.getKey()), any(ActionListener.class));
        }
    }

    private void verifyVersionPredicate(String principal, Predicate<Version> versionPredicate) {
        assertThat(versionPredicate.test(Version.V_5_0_0_rc1), is(false));
        switch (principal) {
            case LogstashSystemUser.NAME:
                assertThat(versionPredicate.test(Version.V_5_0_0), is(false));
                assertThat(versionPredicate.test(Version.V_5_1_1), is(false));
                assertThat(versionPredicate.test(Version.V_5_2_0), is(true));
                break;
            default:
                assertThat(versionPredicate.test(Version.V_5_0_0), is(true));
                assertThat(versionPredicate.test(Version.V_5_1_1), is(true));
                assertThat(versionPredicate.test(Version.V_5_2_0), is(true));
                break;
        }
        assertThat(versionPredicate.test(Version.V_6_0_0_alpha1), is(true));
    }
}
