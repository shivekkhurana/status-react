(ns status-im.group-chats.core
  (:refer-clojure :exclude [remove])
  (:require [clojure.spec.alpha :as spec]
            [re-frame.core :as re-frame]
            [status-im.chat.models :as models.chat]
            [status-im.chat.models.message :as models.message]
            [status-im.data-store.chats :as data-store.chats]
            [status-im.data-store.messages :as data-store.messages]
            [status-im.ethereum.json-rpc :as json-rpc]
            [status-im.group-chats.db :as group-chats.db]
            [status-im.multiaccounts.model :as multiaccounts.model]
            [status-im.transport.filters.core :as transport.filters]
            [status-im.navigation :as navigation]
            [status-im.utils.fx :as fx]
            [status-im.waku.core :as waku]
            [status-im.constants :as constants]))

(fx/defn remove-member
  "Format group update message and sign membership"
  [{:keys [db] :as cofx} chat-id member]
  {::json-rpc/call [{:method (json-rpc/call-ext-method (waku/enabled? cofx) "removeMemberFromGroupChat")
                     :params [nil chat-id member]
                     :on-success #(re-frame/dispatch [::chat-updated %])}]})

(fx/defn set-up-filter
  "Listen/Tear down the shared topic/contact-codes. Stop listening for members who
  have left the chat"
  [cofx chat-id previous-chat]
  (let [my-public-key (multiaccounts.model/current-public-key cofx)
        new-chat (get-in cofx [:db :chats chat-id])
        members (:members-joined new-chat)]
    ;; If we left the chat do nothing
    (when-not (and (group-chats.db/joined? my-public-key previous-chat)
                   (not (group-chats.db/joined? my-public-key new-chat)))
      (fx/merge
       cofx
       (transport.filters/upsert-group-chat-topics)
       (transport.filters/load-members members)))))

(fx/defn handle-chat-update
  {:events [::chat-updated]}
  [cofx {:keys [chats messages]}]
  (let [{:keys [chat-id] :as chat} (-> chats
                                       first
                                       (data-store.chats/<-rpc))

        previous-chat (get-in cofx [:chats chat-id])
        set-up-filter-fx (set-up-filter chat-id previous-chat)
        chat-fx (models.chat/ensure-chat
                 (dissoc chat :unviewed-messages-count))
        messages-fx (map #(models.message/receive-one
                           (data-store.messages/<-rpc %))
                         messages)
        navigate-fx #(if (get-in % [:db :chats chat-id :is-active])
                       (models.chat/navigate-to-chat % chat-id)
                       (navigation/navigate-to-cofx % :home {}))]

    (apply fx/merge cofx (concat [chat-fx]
                                 messages-fx
                                 [navigate-fx]))))

(fx/defn join-chat
  [cofx chat-id]
  {::json-rpc/call [{:method (json-rpc/call-ext-method (waku/enabled? cofx) "confirmJoiningGroup")
                     :params [chat-id]
                     :on-success #(re-frame/dispatch [::chat-updated %])}]})

(fx/defn create
  [{:keys [db] :as cofx} group-name]
  (let [selected-contacts (:group/selected-contacts db)]
    {::json-rpc/call [{:method (json-rpc/call-ext-method (waku/enabled? cofx) "createGroupChatWithMembers")
                       :params [nil group-name (into [] selected-contacts)]
                       :on-success #(re-frame/dispatch [::chat-updated %])}]}))

(fx/defn create-from-link
  [cofx {:keys [chat-id invitation-admin chat-name]}]
  (if (get-in cofx [:db :chats chat-id :is-active])
    (models.chat/navigate-to-chat cofx chat-id)
    {::json-rpc/call [{:method (json-rpc/call-ext-method (waku/enabled? cofx) "createGroupChatFromInvitation")
                       :params [chat-name chat-id invitation-admin]
                       :on-success #(re-frame/dispatch [::chat-updated %])}]}))

(fx/defn make-admin
  [{:keys [db] :as cofx} chat-id member]
  {::json-rpc/call [{:method (json-rpc/call-ext-method (waku/enabled? cofx) "addAdminsToGroupChat")
                     :params [nil chat-id [member]]
                     :on-success #(re-frame/dispatch [::chat-updated %])}]})

(fx/defn add-members
  "Add members to a group chat"
  [{{:keys [current-chat-id selected-participants]} :db :as cofx}]
  {::json-rpc/call [{:method (json-rpc/call-ext-method (waku/enabled? cofx) "addMembersToGroupChat")
                     :params [nil current-chat-id selected-participants]
                     :on-success #(re-frame/dispatch [::chat-updated %])}]})

(fx/defn add-members-from-invitation
  "Add members to a group chat"
  {:events [:group-chats.ui/add-members-from-invitation]}
  [{{:keys [current-chat-id] :as db} :db :as cofx} id participant]
  {:db             (assoc-in db [:group-chat/invitations id :state] constants/invitation-state-approved)
   ::json-rpc/call [{:method     (json-rpc/call-ext-method (waku/enabled? cofx) "addMembersToGroupChat")
                     :params     [nil current-chat-id [participant]]
                     :on-success #(re-frame/dispatch [::chat-updated %])}]})

(fx/defn leave
  "Leave chat"
  {:events [:group-chats.ui/leave-chat-confirmed]}
  [{:keys [db] :as cofx} chat-id]
  {::json-rpc/call [{:method (json-rpc/call-ext-method (waku/enabled? cofx) "leaveGroupChat")
                     :params [nil chat-id true]
                     :on-success #(re-frame/dispatch [::chat-updated %])}]})

(fx/defn remove
  "Remove chat"
  {:events [:group-chats.ui/remove-chat-confirmed]}
  [cofx chat-id]
  (fx/merge cofx
            (models.chat/deactivate-chat chat-id)
            (models.chat/upsert-chat {:chat-id   chat-id
                                      :is-active false}
                                     nil)
            (navigation/navigate-to-cofx :home {})))

(defn- valid-name? [name]
  (spec/valid? :profile/name name))

(fx/defn name-changed
  "Save chat from edited profile"
  {:events [:group-chats.ui/name-changed]}
  [{:keys [db] :as cofx} chat-id new-name]
  (when (valid-name? new-name)
    {:db (assoc-in db [:chats chat-id :name] new-name)
     ::json-rpc/call [{:method (json-rpc/call-ext-method (waku/enabled? cofx) "changeGroupChatName")
                       :params [nil chat-id new-name]
                       :on-success #(re-frame/dispatch [::chat-updated %])}]}))

(fx/defn membership-retry
  {:events [:group-chats.ui/membership-retry]}
  [{{:keys [current-chat-id] :as db} :db}]
  {:db (assoc-in db [:chat/memberships current-chat-id :retry?] true)})

(fx/defn membership-message
  {:events [:group-chats.ui/update-membership-message]}
  [{{:keys [current-chat-id] :as db} :db} message]
  {:db (assoc-in db [:chat/memberships current-chat-id :message] message)})

(fx/defn send-group-chat-membership-request
  "Send group chat membership request"
  {:events [:send-group-chat-membership-request]}
  [{{:keys [current-chat-id chats] :as db} :db :as cofx}]
  (let [{:keys [invitation-admin]} (get chats current-chat-id)
        message (get-in db [:chat/memberships current-chat-id :message])]
    {:db (assoc-in db [:chat/memberships current-chat-id] nil)
     ::json-rpc/call [{:method (json-rpc/call-ext-method (waku/enabled? cofx) "sendGroupChatInvitationRequest")
                       :params [nil current-chat-id invitation-admin message]
                       :on-success #(re-frame/dispatch [:transport/invitation-sent %])}]}))

(fx/defn send-group-chat-membership-rejection
  "Send group chat membership rejection"
  {:events [:send-group-chat-membership-rejection]}
  [cofx invitation-id]
  {::json-rpc/call [{:method (json-rpc/call-ext-method (waku/enabled? cofx) "sendGroupChatInvitationRejection")
                     :params [nil invitation-id]
                     :on-success #(re-frame/dispatch [:transport/invitation-sent %])}]})

(fx/defn handle-invitations
  [{db :db} invitations]
  {:db (update db :group-chat/invitations #(reduce (fn [acc {:keys [id] :as inv}]
                                                     (assoc acc id inv))
                                                   %
                                                   invitations))})
