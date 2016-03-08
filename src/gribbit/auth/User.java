/**
 * This file is part of the Gribbit Web Framework.
 * 
 *     https://github.com/lukehutch/gribbit
 * 
 * @author Luke Hutchison
 * 
 * --
 * 
 * @license Apache 2.0 
 * 
 * Copyright 2015 Luke Hutchison
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gribbit.auth;

import org.mongojack.MongoCollection;

import gribbit.handler.route.annotation.Roles;
import gribbit.model.DBModelStringKey;
import gribbit.server.siteresources.Database;
import io.vertx.ext.web.Session;

/**
 * Used to store user identity and authentication information in the database. The id field (defined in superclass)
 * is the OAuth ID.
 */
@MongoCollection(name = "users")
public class User extends DBModelStringKey {

    public String csrfTok;
    public String[] roles;

    // -----------------------------------------------------------------------------------------------------------------

    private static final String USERID_SESSION_KEY = "_userid";
    public static final String CSRF_TOK_SESSION_KEY = "_csrf_tok";
    private static final String ROLES_SESSION_KEY = "_roles";

    public User() {
    }

    private User(String userId, String csrfTok, String[] roles) {
        super(userId);
        this.csrfTok = csrfTok;
        this.roles = roles;
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Gets the user from the session, or null if there is no user logged in. */
    public static User fromSession(Session session) {
        String userId = session.get(USERID_SESSION_KEY);
        String csrfTok = session.get(CSRF_TOK_SESSION_KEY);
        String rolesStr = session.get(ROLES_SESSION_KEY);
        String[] roles = null;
        if (rolesStr != null) {
            roles = rolesStr.split("\t");
        }
        if (userId != null && !userId.isEmpty() && csrfTok != null && !csrfTok.isEmpty()) {
            return new User(userId, csrfTok, roles);
        } else {
            return null;
        }
    }

    private void toSession(Session session) {
        session.put(USERID_SESSION_KEY, id);
        session.put(CSRF_TOK_SESSION_KEY, csrfTok);
        session.put(ROLES_SESSION_KEY, String.join("\t", roles));
    }

    /** Log in existing user. If user does not yet exist, returns null. */
    public static User logIn(String userId, Session session) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("Invalid userId");
        }
        User user = Database.findOneById(User.class, userId);
        if (user != null) {
            user.toSession(session);
        }
        return user;
    }

    /** Create a new user. Call this if login() returned null. */
    public static User create(String userId, String[] roles, Session session) {
        User user = new User(userId, CSRF.generateRandomCSRFToken(), roles);
        user.save();
        user.toSession(session);
        return user;
    }

    /** Invalidate login session for user. */
    public static void logOut(Session session) {
        session.destroy();
    }

    /**
     * Test if a user is authorized for the Roles annotation on a given RouteHandler class.
     * 
     * If there is no Roles annotation (i.e. if roles == null), the user must at least be logged in (i.e. user
     * cannot be null).
     * 
     * Otherwise, the user must be logged in, and must have one of the roles listed in the annotation for this
     * function to return true.
     */
    public static boolean userIsAuthorized(User user, Roles roles) {
        if (user != null && (roles == null || roles.value().length == 0)) {
            // There are no roles specified, and user is logged in
            return true;
        }
        if (user != null && user.roles != null && roles != null && roles.value() != null) {
            for (String userRole : user.roles) {
                for (String reqdRole : roles.value()) {
                    if (userRole.equals(reqdRole) && !userRole.isEmpty() && !reqdRole.isEmpty()) {
                        // User has one of the required roles
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
