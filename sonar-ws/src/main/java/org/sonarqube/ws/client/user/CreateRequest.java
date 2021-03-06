/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonarqube.ws.client.user;

import java.util.List;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Collections.emptyList;

public class CreateRequest {

  private final String login;
  private final String password;
  private final String name;
  private final String email;
  private final List<String> scmAccounts;

  public CreateRequest(Builder builder) {
    this.login = builder.login;
    this.password = builder.password;
    this.name = builder.name;
    this.email = builder.email;
    this.scmAccounts = builder.scmAccounts;
  }

  public String getLogin() {
    return login;
  }

  public String getPassword() {
    return password;
  }

  public String getName() {
    return name;
  }

  @CheckForNull
  public String getEmail() {
    return email;
  }

  public List<String> getScmAccounts() {
    return scmAccounts;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String login;
    private String password;
    private String name;
    private String email;
    private List<String> scmAccounts = emptyList();

    private Builder() {
      // enforce factory method use
    }

    public Builder setLogin(String login) {
      this.login = login;
      return this;
    }

    public Builder setPassword(String password) {
      this.password = password;
      return this;
    }

    public Builder setName(String name) {
      this.name = name;
      return this;
    }

    public Builder setEmail(@Nullable String email) {
      this.email = email;
      return this;
    }

    public Builder setScmAccounts(List<String> scmAccounts) {
      this.scmAccounts = scmAccounts;
      return this;
    }

    public CreateRequest build() {
      checkArgument(!isNullOrEmpty(login), "Login is mandatory and must not be empty");
      checkArgument(!isNullOrEmpty(password), "Password is mandatory and must not be empty");
      checkArgument(!isNullOrEmpty(name), "Name is mandatory and must not be empty");
      return new CreateRequest(this);
    }
  }
}
