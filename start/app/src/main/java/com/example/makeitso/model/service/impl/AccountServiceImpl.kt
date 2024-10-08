/*
Copyright 2022 Google LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package com.example.makeitso.model.service.impl

import com.example.makeitso.model.User
import com.example.makeitso.model.service.AccountService
import com.example.makeitso.model.service.trace
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class AccountServiceImpl @Inject constructor(private val auth: FirebaseAuth, private val firestore: FirebaseFirestore
) : AccountService {

  override val currentUserId: String
    get() = auth.currentUser?.uid.orEmpty()

  override val currentUserData: User
    get() {
      val user = User(userId = currentUserId)
      return user
    }

  override val hasUser: Boolean
    get() = auth.currentUser != null && !auth.currentUser!!.isAnonymous

  override val currentUser: Flow<User>
    get() = callbackFlow {
      val listener =
        FirebaseAuth.AuthStateListener { auth ->
          this.trySend(auth.currentUser?.let { User(it.uid, it.isAnonymous) } ?: User())
        }
      auth.addAuthStateListener(listener)
      awaitClose { auth.removeAuthStateListener(listener) }
    }

  override suspend fun authenticate(email: String, password: String) {
    auth.signInWithEmailAndPassword(email, password).await()
    val newUser = User(userId = currentUserId, authMethod = MAIL_LOGIN_TYPE, isAnonymous = false)
    newUser.login = email
    saveCurrentUserData(newUser)
  }

  override suspend fun sendRecoveryEmail(email: String) {
    auth.sendPasswordResetEmail(email).await()
  }

  override suspend fun linkAccount(email: String, password: String) {
    val credential = EmailAuthProvider.getCredential(email, password)
    auth.signInAnonymously().await()
    auth.currentUser!!.linkWithCredential(credential).await()
    createUserFromMail(email)
  }

  override suspend fun createUserFromCredentials(credential: AuthCredential) {
    val newUser = User(userId = currentUserId, authMethod = GOOGLE_LOGIN_TYPE, isAnonymous = false)

    saveCurrentUserData(newUser)
  }

  override suspend fun createUserFromMail(email: String) {
    val newUser = User(userId = currentUserId, authMethod = MAIL_LOGIN_TYPE, isAnonymous = false)
    newUser.login = email
    saveCurrentUserData(newUser)
  }

  override suspend fun createUserFromId(id: String) {

    val document = firestore.collection(USER_COLLECTION).whereEqualTo("userId", id).get().await()
    val user = document.first().toObject(User::class.java)
    saveCurrentUserData(user)
  }

  override suspend fun deleteAccount() {
    deleteCurrentUserData(currentUserData.userId)
    auth.currentUser!!.delete().await()
  }

  override suspend fun signOut() {
    if (auth.currentUser!!.isAnonymous) {
      auth.currentUser!!.delete()
    }
    auth.signOut()
  }

  override suspend fun getCurrentUserData(): User {
    return this.currentUserData
  }

  override suspend fun saveCurrentUserData(user: User) {
    trace(SAVE_USER_DATA_TRACE) {
      val userWithUserId = user.copy(userId = currentUserId)
      val cnt = firestore.collection(USER_COLLECTION).whereEqualTo("userId", currentUserId).get().await()
      if (cnt.size() == 0){
        firestore.collection(USER_COLLECTION).add(userWithUserId).await().id
      }
    }
  }

  override suspend fun deleteCurrentUserData(id: String) {
    val documentId = firestore.collection(USER_COLLECTION).whereEqualTo("userId", id).get().await().first().id
    firestore.collection(USER_COLLECTION).document(documentId).delete().await()
  }

  companion object {
    private const val USER_COLLECTION = "users"
    private const val LINK_ACCOUNT_TRACE = "linkAccount"
    private const val SAVE_USER_DATA_TRACE = "saveUserData"
    private const val MAIL_LOGIN_TYPE = "mail"
    private const val GOOGLE_LOGIN_TYPE = "google"
  }
}
